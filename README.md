# web-research

Android WebView-инструмент для максимально полного захвата текущей браузерной сессии, последующего экспорта исходных данных в ZIP и ручного анализа.

<!-- AUTO-RELEASE-START -->
## Текущий релиз

- Версия: **v25**
- `versionCode`: **25**
- `versionName`: **v25**
- package: `web.research`
- commit: `485dcfbfd37a54e87174ed7551f577d8373b6421`
- APK: `web-research-v25.apk`
- SHA-256: `dbfcf5e671790d6fa1d3a2088fcc34675d40a8514de91c40d1a9760bc0519833`
- Опубликован: `2026-09-10T10:06:39Z`
- Предыдущий релиз: **v24**
- Release: https://github.com/lvlaksim1/web-research/releases/tag/v25
- APK: https://github.com/lvlaksim1/web-research/releases/download/v25/web-research-v25.apk
<!-- AUTO-RELEASE-END -->

## Назначение

Приложение открывает сайты внутри Android WebView и сохраняет доступные свидетельства реальной сессии без попытки автоматически превращать её в Postman-коллекцию или универсальный AUTH-replay.

Критический принцип: сырые события сначала добавляются в `ResearchArchive`, и только после этого передаются в слой корреляции и отображения сетевого debugger-а. Производные представления не должны заменять или изменять исходный архив.

## Что фиксируется

- WebView resource requests и копирование доступных ресурсов;
- `fetch` и `XMLHttpRequest`: URL, метод, request/response headers, request/response body, status, timing и final URL;
- navigation/history и действия пользователя;
- cookies, `localStorage`, `sessionStorage`;
- full/light page snapshots, DOM и HTML страницы;
- JavaScript-файлы и динамические inline scripts;
- resource/navigation timing и performance events;
- DOM mutations;
- WebSocket и Server-Sent Events;
- JavaScript errors и unhandled promise rejections;
- доступные сведения о скачиваниях файлов.

## ZIP-экспорт

Основной результат исследования — ZIP текущей сессии. В него входят, в частности:

- `raw-events.json` — исходный журнал событий;
- `network.har`;
- `api-summary.json`;
- `actions.json`;
- `dom-mutations.json`;
- `realtime.json`;
- `performance.json`;
- `page-snapshot.json`;
- `page.html`, если HTML доступен;
- архивированные JavaScript-файлы и `js/manifest.json`;
- сохранённые ресурсы и `resources/manifest.json`.

## Сетевой debugger

Сетевой экран предназначен для анализа уже собранных данных. Он поддерживает фильтрацию по домену, методу и типу ответа, поиск, объединённое/раздельное отображение, request/response headers и bodies, JSON-просмотр, URL decode, cURL, GET BODY и EDIT / REPLAY.

## Скачивание файлов

Если сайт инициирует скачивание через WebView DownloadListener, приложение передаёт его Android DownloadManager, добавляя доступные User-Agent, Cookie и Referer. Сам факт скачивания также фиксируется как событие исследования.

## Android-конфигурация

- `applicationId`: `web.research`
- `minSdk`: 26
- `targetSdk`: 35
- `compileSdk`: 35
- Java/Kotlin target: 17
- номер релиза одновременно используется как `versionCode`; `versionName = v<versionCode>`
- APK подписываются постоянной signing identity из GitHub Secret `ANDROID_KEYSTORE`

## Стандартная документация

- `ARCHITECTURE.md` — текущая архитектура приложения, границы компонентов и инварианты.
- `CHANGELOG.md` — накопительная история релизов.
- `RELEASE.md` — правила сборки, проверки, публикации и сопровождения релизов.
- `.release/latest.json` — канонические машиночитаемые metadata последнего опубликованного релиза.

После каждого успешного релиза workflow обновляет только стандартизированные автоматически управляемые части этих документов и делает отдельный docs commit без `[release]`.
