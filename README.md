# Расписание

Приложение показывает [сайт расписания](https://raspisanie.nikasoft.ru/15312761.html) МБОУ СШ №6 (г. Гуково). Хост раз в 5 минут проверяет, сменилась ли выгрузка, и шлёт уведомление в приложение. Telegram не используется.

## Хост

```bash
cd host
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
cp .env.example .env
./venv/bin/python monitor.py
```

Телефон должен открывать адрес хоста, например `http://IP:8080/health`. Пока в `.env` не указан Firebase, монитор сайт проверяет, но push отправить не может.

Ключи Firebase в репозиторий не клади.

1. В [Firebase Console](https://console.firebase.google.com) создай проект.
2. Добавь Android-приложение с пакетом `ru.gukovo.school6.schedule`.
3. Скачай `google-services.json` в `android/app/` на компьютере, где собираешь APK. В git его не добавляй.
4. В настройках проекта открой Service accounts и скачай закрытый ключ.
5. Положи ключ на хост и укажи в `.env`: `FCM_SERVICE_ACCOUNT` и `FCM_PROJECT_ID`.
6. Перезапусти монитор.

Служба: `host/raspisanie-monitor.service`. Поправь пути под свой сервер.

Если старый Telegram-бот ещё запущен, он продолжит писать в Telegram сам. Этот монитор его не вызывает. Останови бота, если сообщения в Telegram больше не нужны.

## Приложение

Открой `android/` в Android Studio. Для уведомлений при закрытом приложении сначала положи `google-services.json`, потом собирай APK. Сборка из GitHub Actions этот файл не содержит, поэтому push в ней не заработает.

После установки открой меню «Сервер уведомлений» и впиши адрес хоста. Сайт расписания открывается и без этого.
