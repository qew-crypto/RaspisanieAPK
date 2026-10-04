#!/usr/bin/env python3
"""Проверяет расписание и шлёт push в Android-приложение."""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import requests

MSK = timezone(timedelta(hours=3))
ROOT = Path(__file__).resolve().parent
LOCK = threading.Lock()
MAX_DEVICES = 200
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

logging.basicConfig(format="%(asctime)s %(levelname)s %(message)s", level=logging.INFO)
logger = logging.getLogger("monitor")
FCM_CREDS = None


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def check_url() -> str:
    return os.getenv("CHECK_URL", "https://raspisanie.nikasoft.ru/check/15312761.html").strip()


def data_base_url() -> str:
    return os.getenv("DATA_BASE_URL", "https://raspisanie.nikasoft.ru/static/public/").strip()


def schedule_url() -> str:
    return os.getenv("SCHEDULE_URL", "https://raspisanie.nikasoft.ru/15312761.html").strip()


def data_dir() -> Path:
    raw = os.getenv("DATA_DIR", "data").strip() or "data"
    path = Path(raw)
    if not path.is_absolute():
        path = ROOT / path
    path.mkdir(parents=True, exist_ok=True)
    return path


def check_interval() -> int:
    try:
        seconds = int(os.getenv("CHECK_INTERVAL", "300").strip() or "300")
    except ValueError:
        seconds = 300
    return max(60, seconds)


def state_path() -> Path:
    return data_dir() / "state.json"


def devices_path() -> Path:
    return data_dir() / "devices.json"


def load_json(path: Path, fallback):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return fallback


def save_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def get_schedule_info() -> dict:
    response = requests.get(check_url(), timeout=15)
    response.raise_for_status()
    schedule_id = response.text.strip()
    if not schedule_id or "/" in schedule_id or ".." in schedule_id:
        raise RuntimeError("Сайт вернул неожиданный идентификатор расписания")
    payload = requests.get(data_base_url() + schedule_id, timeout=15)
    payload.raise_for_status()
    text = payload.text

    def pick(key: str) -> str | None:
        match = re.search(rf'"{key}"\s*:\s*"([^"]+)"', text)
        return match.group(1) if match else None

    return {
        "schedule_id": schedule_id,
        "export_date": pick("EXPORT_DATE"),
        "export_time": pick("EXPORT_TIME"),
        "school_name": pick("SCHOOL_NAME") or "Школа",
        "city_name": pick("CITY_NAME") or "",
    }


def relative_when(info: dict) -> str:
    export_date = info.get("export_date") or ""
    export_time = info.get("export_time") or ""
    short = export_time[:5]
    try:
        moment = datetime.strptime(f"{export_date} {export_time}", "%d.%m.%Y %H:%M:%S").replace(tzinfo=MSK)
    except ValueError:
        return f"{export_date} в {short}".strip()
    today = datetime.now(MSK).date()
    if moment.date() == today:
        return f"сегодня в {short}"
    if moment.date() == today - timedelta(days=1):
        return f"вчера в {short}"
    return f"{export_date} в {short}"


def public_status() -> dict:
    with LOCK:
        state = load_json(state_path(), {})
        devices = load_json(devices_path(), [])
    status = {
        "schedule_id": state.get("last_schedule_id"),
        "export_date": state.get("export_date"),
        "export_time": state.get("export_time"),
        "school_name": state.get("school_name"),
        "city_name": state.get("city_name"),
        "relative": relative_when(state) if state.get("export_date") else None,
        "schedule_url": schedule_url(),
        "devices": len(devices) if isinstance(devices, list) else 0,
    }
    return status


def remember(info: dict) -> str | None:
    with LOCK:
        state = load_json(state_path(), {})
        previous = state.get("last_schedule_id")
        state.update(info)
        state["last_schedule_id"] = info["schedule_id"]
        save_json(state_path(), state)
        return previous


def load_devices() -> list[str]:
    with LOCK:
        data = load_json(devices_path(), [])
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, str) and item.strip()]


def add_device(token: str) -> None:
    token = token.strip()
    if not token or len(token) > 4096:
        raise ValueError("пустой токен")
    with LOCK:
        devices = load_json(devices_path(), [])
        if not isinstance(devices, list):
            devices = []
        if token not in devices:
            if len(devices) >= MAX_DEVICES:
                raise ValueError("слишком много устройств")
            devices.append(token)
            save_json(devices_path(), devices)


def remove_device(token: str) -> None:
    with LOCK:
        devices = load_json(devices_path(), [])
        if not isinstance(devices, list):
            return
        save_json(devices_path(), [item for item in devices if item != token])


def fcm_access_token() -> str | None:
    global FCM_CREDS
    raw = os.getenv("FCM_SERVICE_ACCOUNT", "").strip()
    if not raw:
        return None
    path = Path(raw)
    if not path.is_absolute():
        path = ROOT / path
    if not path.exists():
        return None
    if FCM_CREDS is None:
        from google.oauth2 import service_account

        FCM_CREDS = service_account.Credentials.from_service_account_file(path, scopes=[FCM_SCOPE])
    if not FCM_CREDS.valid:
        from google.auth.transport.requests import Request

        FCM_CREDS.refresh(Request())
    return FCM_CREDS.token


def send_push(token: str, title: str, body: str, schedule_id: str) -> str:
    project = os.getenv("FCM_PROJECT_ID", "").strip()
    access = fcm_access_token()
    if not project or not access:
        logger.warning("Firebase не настроен, push пропущен")
        return "skipped"
    response = requests.post(
        f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
        headers={"Authorization": f"Bearer {access}"},
        json={
            "message": {
                "token": token,
                "data": {
                    "title": title,
                    "body": body,
                    "schedule_id": schedule_id,
                    "url": schedule_url(),
                },
                "android": {"priority": "HIGH"},
            }
        },
        timeout=20,
    )
    if response.status_code == 200:
        return "ok"
    text = response.text
    if response.status_code in (400, 404) and any(mark in text for mark in ("UNREGISTERED", "NOT_FOUND", "InvalidRegistration")):
        return "gone"
    logger.warning("FCM %s для ...%s: %s", response.status_code, token[-6:], text[:300])
    return "error"


def push_all(info: dict) -> None:
    title = "Расписание обновлено"
    body = f"Обновлено {relative_when(info)}"
    for token in load_devices():
        try:
            result = send_push(token, title, body, info["schedule_id"])
        except requests.RequestException as exc:
            logger.warning("Push не отправился ...%s: %s", token[-6:], exc)
            continue
        if result == "gone":
            remove_device(token)
            logger.info("Удалил недействительный токен ...%s", token[-6:])
        elif result == "ok":
            logger.info("Push отправлен ...%s", token[-6:])


def check_once() -> None:
    info = get_schedule_info()
    previous = remember(info)
    if previous and previous != info["schedule_id"]:
        logger.info("Расписание обновилось: %s -> %s", previous, info["schedule_id"])
        push_all(info)
    else:
        logger.info("Без изменений: %s", info["schedule_id"])


def checker() -> None:
    while True:
        try:
            check_once()
        except Exception:
            logger.exception("Проверка не удалась")
        time.sleep(check_interval())


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._json(200, {"ok": True})
        elif path == "/status":
            self._json(200, public_status())
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] != "/devices":
            self._json(404, {"error": "not found"})
            return
        body = self._body()
        token = str(body.get("token") or "").strip()
        try:
            add_device(token)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
            return
        logger.info("Устройство зарегистрировано ...%s", token[-6:])
        self._json(200, {"ok": True})

    def do_DELETE(self) -> None:
        if self.path.split("?", 1)[0] != "/devices":
            self._json(404, {"error": "not found"})
            return
        token = str(self._body().get("token") or "").strip()
        if token:
            remove_device(token)
        self._json(200, {"ok": True})

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length < 0 or length > 4096:
            return {}
        raw = self.rfile.read(length) if length else b""
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            return {}
        return data if isinstance(data, dict) else {}

    def _json(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt: str, *args) -> None:
        logger.info("%s - %s", self.address_string(), fmt % args)


def main() -> None:
    load_dotenv(ROOT / ".env")
    port = int(os.getenv("PORT", "8080").strip() or "8080")
    bind = os.getenv("BIND", "0.0.0.0").strip() or "0.0.0.0"
    threading.Thread(target=checker, name="checker", daemon=True).start()
    try:
        server = ThreadingHTTPServer((bind, port), Handler)
    except OSError as exc:
        raise SystemExit(f"Не удалось занять {bind}:{port}: {exc}") from exc
    logger.info("Монитор слушает http://%s:%s", bind, port)
    logger.info("Проверка каждые %s сек", check_interval())
    server.serve_forever()


if __name__ == "__main__":
    main()
