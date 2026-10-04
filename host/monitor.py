#!/usr/bin/env python3
"""Монитор расписания МБОУ СШ №6.

Раз в 5 минут читает дату выгрузки с сайта и, если она сменилась,
пишет подписчикам в Telegram. Токен только из окружения или host/.env.
"""

from __future__ import annotations

import argparse
import asyncio
import html
import json
import logging
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests
from telegram import Update
from telegram.constants import ParseMode
from telegram.error import Forbidden, TelegramError
from telegram.ext import Application, CommandHandler, ContextTypes

MSK = timezone(timedelta(hours=3))
ROOT = Path(__file__).resolve().parent
BROADCAST_PAUSE = 0.05

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("monitor")


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise SystemExit(f"Не задан {name}. Положи его в host/.env, в репозиторий не коммить.")
    return value


def schedule_url() -> str:
    return os.getenv("SCHEDULE_URL", "https://raspisanie.nikasoft.ru/15312761.html").strip()


def check_url() -> str:
    return os.getenv("CHECK_URL", "https://raspisanie.nikasoft.ru/check/15312761.html").strip()


def data_base_url() -> str:
    return os.getenv("DATA_BASE_URL", "https://raspisanie.nikasoft.ru/static/public/").strip()


def data_dir() -> Path:
    raw = os.getenv("DATA_DIR", "data").strip() or "data"
    path = Path(raw)
    if not path.is_absolute():
        path = ROOT / path
    path.mkdir(parents=True, exist_ok=True)
    return path


def check_interval() -> int:
    raw = os.getenv("CHECK_INTERVAL", "300").strip() or "300"
    try:
        seconds = int(raw)
    except ValueError:
        seconds = 300
    return max(60, seconds)


def subscribers_file() -> Path:
    return data_dir() / "subscribers.json"


def state_file() -> Path:
    return data_dir() / "state.json"


def load_subscribers() -> set[int]:
    try:
        data = json.loads(subscribers_file().read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return set()
    result = set()
    for item in data:
        try:
            result.add(int(item))
        except (TypeError, ValueError):
            continue
    return result


def save_subscribers(subs: set[int]) -> None:
    subscribers_file().write_text(
        json.dumps(sorted(subs), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def load_last_schedule_id() -> str | None:
    try:
        return json.loads(state_file().read_text(encoding="utf-8")).get("last_schedule_id")
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def save_last_schedule_id(schedule_id: str) -> None:
    state_file().write_text(
        json.dumps({"last_schedule_id": schedule_id}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


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
        "export_date": pick("EXPORT_DATE"),
        "export_time": pick("EXPORT_TIME"),
        "school_name": pick("SCHOOL_NAME") or "Школа",
        "city_name": pick("CITY_NAME") or "",
        "schedule_id": schedule_id,
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


def format_update_message(info: dict) -> str:
    if not info.get("export_date") or not info.get("export_time"):
        return "Не удалось прочитать дату обновления."
    school = html.escape(info.get("school_name") or "Школа")
    city = html.escape(info.get("city_name") or "")
    header = school + (f" ({city})" if city else "")
    return (
        "━━━━━━━━━━━━━━━━━━━━\n"
        f"🏫  <b>{header}</b>\n"
        "━━━━━━━━━━━━━━━━━━━━\n\n"
        f"📅  Обновлено <b>{html.escape(relative_when(info))}</b>\n\n"
        f"🗓  Дата:  <code>{html.escape(info['export_date'])}</code>\n"
        f"🕐  Время:  <code>{html.escape(info['export_time'])}</code>\n"
        "━━━━━━━━━━━━━━━━━━━━"
    )


def notification_text(info: dict) -> str:
    return (
        "━━━━━━━━━━━━━━━━━━━━\n"
        "🔔  <b>Расписание обновлено!</b>\n"
        "━━━━━━━━━━━━━━━━━━━━\n\n"
        + format_update_message(info)
        + f'\n\n🌐 <a href="{html.escape(schedule_url())}">Открыть расписание</a>'
    )


def send_message(token: str, chat_id: int, text: str) -> None:
    response = requests.post(
        f"https://api.telegram.org/bot{token}/sendMessage",
        json={
            "chat_id": chat_id,
            "text": text,
            "parse_mode": "HTML",
            "disable_web_page_preview": True,
        },
        timeout=20,
    )
    if response.status_code == 403:
        raise Forbidden(response.text)
    response.raise_for_status()
    body = response.json()
    if not body.get("ok"):
        raise TelegramError(body.get("description") or "Telegram отклонил сообщение")


def notify_subscribers(token: str, info: dict) -> None:
    text = notification_text(info)
    for chat_id in sorted(load_subscribers()):
        try:
            send_message(token, chat_id, text)
        except Forbidden:
            logger.info("Подписчик %s заблокировал бота, удаляю", chat_id)
            subs = load_subscribers()
            subs.discard(chat_id)
            save_subscribers(subs)
        except (requests.RequestException, TelegramError) as exc:
            logger.warning("Не отправилось %s: %s", chat_id, exc)


def check_once(token: str) -> int:
    info = get_schedule_info()
    current = info["schedule_id"]
    previous = load_last_schedule_id()
    if previous == current:
        logger.info("Без изменений: %s", current)
        return 0
    save_last_schedule_id(current)
    if not previous:
        logger.info("Первая проверка, уведомление не шлю: %s", current)
        return 0
    logger.info("Расписание обновилось: %s -> %s", previous, current)
    notify_subscribers(token, info)
    return 0


async def watch(token: str) -> None:
    previous = load_last_schedule_id()
    if not previous:
        try:
            previous = get_schedule_info()["schedule_id"]
            save_last_schedule_id(previous)
            logger.info("Начальный id: %s", previous)
        except (requests.RequestException, RuntimeError) as exc:
            logger.error("Стартовая проверка не удалась: %s", exc)
    interval = check_interval()
    while True:
        await asyncio.sleep(interval)
        try:
            info = get_schedule_info()
        except (requests.RequestException, RuntimeError) as exc:
            logger.error("Проверка не удалась: %s", exc)
            continue
        current = info["schedule_id"]
        if current == previous:
            continue
        logger.info("Расписание обновилось: %s -> %s", previous, current)
        previous = current
        save_last_schedule_id(current)
        await asyncio.to_thread(notify_subscribers, token, info)


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if chat is None:
        return
    subs = load_subscribers()
    subs.add(chat.id)
    save_subscribers(subs)
    await update.effective_message.reply_text(
        "Ты подписан на обновления расписания.\n"
        "Сообщение придёт, когда на сайте сменится выгрузка.\n"
        "Отписка — /stop",
    )


async def cmd_stop(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if chat is None:
        return
    subs = load_subscribers()
    subs.discard(chat.id)
    save_subscribers(subs)
    await update.effective_message.reply_text("Подписка выключена.")


async def cmd_update(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    try:
        info = await asyncio.to_thread(get_schedule_info)
        text = format_update_message(info)
    except (requests.RequestException, RuntimeError):
        text = "Сейчас не получилось прочитать сайт расписания."
    await update.effective_message.reply_text(text, parse_mode=ParseMode.HTML, disable_web_page_preview=True)


async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.effective_message.reply_text(
        "/start — подписаться\n"
        "/stop — отписаться\n"
        "/update — когда сайт последний раз обновил расписание\n"
        "/id — твой chat id"
    )


async def cmd_id(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if chat is not None:
        await update.effective_message.reply_text(str(chat.id))


def run_bot(token: str) -> None:
    async def post_init(app: Application) -> None:
        asyncio.create_task(watch(token))
        logger.info("Проверка каждые %s сек", check_interval())

    app = Application.builder().token(token).post_init(post_init).build()
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("stop", cmd_stop))
    app.add_handler(CommandHandler("update", cmd_update))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(CommandHandler("id", cmd_id))
    app.run_polling(drop_pending_updates=True)


def main() -> int:
    load_dotenv(ROOT / ".env")
    parser = argparse.ArgumentParser(description="Монитор расписания")
    parser.add_argument("--once", action="store_true", help="Одна проверка и выход")
    args = parser.parse_args()
    token = required("BOT_TOKEN")
    if args.once:
        return check_once(token)
    run_bot(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
