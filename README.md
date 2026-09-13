# web-research

Android WebView-инструмент для максимально полного захвата текущей браузерной сессии, последующего экспорта исходных данных в ZIP и ручного анализа.

<!-- AUTO-RELEASE-START -->
## Текущий релиз

- Версия: **v48**
- `versionCode`: **48**
- `versionName`: **v48**
- package: `web.research`
- commit: `9f1ee29c2921255cca4d9e74e0c4b2eadbe1a4d3`
- APK: `web-research-v48.apk`
- SHA-256: `00246be0fafc7c20c0144a4cc131818ebddb62c231f49d919aaf8c7ec95a086f`
- Опубликован: `2026-09-13T23:06:47Z`
- Предыдущий релиз: **v47**
- Release: https://github.com/lvlaksim1/web-research/releases/tag/v48
- APK: https://github.com/lvlaksim1/web-research/releases/download/v48/web-research-v48.apk
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
- runtime UI state: значения form controls, focus/selection, viewport/scroll, iframe inventory/same-origin snapshots и open Shadow DOM;
- JavaScript-файлы и динамические inline scripts;
- resource/navigation timing и performance events;
- DOM mutations;
- WebSocket и Server-Sent Events;
- JavaScript errors и unhandled promise rejections;
- доступные сведения о скачиваниях файлов.

## ZIP-экспорт

Основной результат исследования — ZIP текущей сессии. В него входят, в частности:

- `session-manifest.json` — counters, completeness indicators, фактические предупреждения и явные capture limits;
- `session-manifest.json` также содержит `advancedChannels`: Service Worker, WebSocket/SSE, performance coverage, source-map hints и явные ограничения DNS/TLS/Worker runtime;
- `timeline.json` — компактная единая временная шкала с event/action/request/mutation ID;
- `relations.json` — производные связи action → request → DOM mutation с указанием метода корреляции;
- `relations.json` также содержит JS initiators и causality chains: action → initiator stack → request → DOM mutation, с отдельной маркировкой observed/inferred;
- `checkpoints/index.json` — последовательность автоматических before/after checkpoints;
- `checkpoint-diffs.json` — изменения cookies, storage и DOM между соседними checkpoints;
- `checkpoints/<id>/state.json` и ограниченные viewport screenshots;
- checkpoint state включает runtime form values/checked/selected, focus и selection, scroll/viewport/VisualViewport, iframe inventory + same-origin frame snapshots и open Shadow DOM;
- `checkpoint-diffs.json` отдельно показывает изменения form values, checked/selected, focus/selection, viewport, frames и Shadow DOM со значениями before/after;
- checkpoint/screenshot лимиты начинаются заново при каждом старте ZIP-записи; v46 использует бюджет 80/80 на recording window;
- внешние JavaScript-файлы и ресурсы, уже захваченные в текущей browser session до начала записи, включаются как supporting evidence;
- `browser/cookie-trace.json` экспортируется с событиями, отфильтрованными по recording window;
- `raw-events.json` — исходный журнал событий с forensic ID и capture ordering;
- `network.har` — только HTTP evidence, без checkpoints/performance/errors;
- `api-summary.json` — только application/realtime API sources, без служебных событий;
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
