# web-research

Android WebView-инструмент для максимально полного захвата текущей браузерной сессии, последующего экспорта исходных данных в ZIP и ручного анализа.

<!-- AUTO-RELEASE-START -->
## Текущий релиз

- Версия: **v11**
- `versionCode`: **11**
- `versionName`: **v11**
- package: `web.research`
- commit: `b43bc7abb7866725ef0e1f1f6b97a5100614610b`
- APK: `web-research-v11.apk`
- SHA-256: `3039bfc2a6d3ccd34efd7f41139647c78a21f5eb883c6de9410595960e625d9a`
- Опубликован: `2026-09-09T23:55:57Z`
- Предыдущий релиз: **v10**
- Release: https://github.com/lvlaksim1/web-research/releases/tag/v11
- APK: https://github.com/lvlaksim1/web-research/releases/download/v11/web-research-v11.apk

### Изменения относительно v10

- ci: harden universal APK release standard
- ci: enforce release permissions at workflow boundaries
- ci: allow reusable release permission ceiling in validation
- ci: accept hardened APK release standard [release]
- ci: fix missing release detection
- ci: accept hardened APK release standard [release]

### Изменённые файлы

- `.github/workflows/_release-apk.yml`
- `.github/workflows/_release-core.yml`
- `.github/workflows/android-apk.yml`
- `.github/workflows/validate-work-branches.yml`
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

## Архитектура релиза

Релизный процесс разделён на три последовательных уровня:

1. `.github/workflows/_release-core.yml` — L1: release gate, номер версии, публикация, recovery/rollback и проверка опубликованного артефакта.
2. `.github/workflows/_release-apk.yml` — L2: APK-специфика, проверка unsigned candidate, architecture check, подпись, verification, retention и Telegram.
3. `.github/actions/build-apk/action.yml` — L3: проектная сборка `./gradlew :app:assembleRelease`.

`.github/workflows/android-apk.yml` — точка входа релиза из `main`.  
`.github/workflows/validate-work-branches.yml` — контрольная сборка веток `*-work` через тот же L2 в режиме `validate`.

После каждого успешного нового релиза `README.md` и `REFACTORING.md` автоматически получают актуальные release metadata, список commit-сообщений и список изменённых файлов относительно предыдущего релиза.
