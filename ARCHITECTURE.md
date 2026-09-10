# Architecture

Документ описывает текущее архитектурное состояние `web-research` и ключевые инварианты, которые должны сохраняться при дальнейшем рефакторинге.

<!-- AUTO-RELEASE-START -->
## Контрольная точка документа

- Актуально для релиза: **v12**
- Релизный commit: `05fb86c6dca3abe1d895cf9c53da11bc7c1aa4af`
- Опубликован: `2026-09-10T00:39:13Z`
<!-- AUTO-RELEASE-END -->

## Критический инвариант данных

`ResearchArchive.records` является источником сырых событий для ZIP/HAR и других экспортов.

Последовательность должна оставаться такой:

```text
событие браузера / сети
        ↓
ResearchArchive
        ↓
NetworkRecordPipeline
        ↓
корреляция / debugger storage
        ↓
display-only transformations
```

Корреляция, объединение записей, фильтрация и UI debugger-а не должны заменять, переписывать или сокращать исходный raw archive.

## Текущие границы приложения

- `WebResearchV10Activity` — основной экран браузера и верхнеуровневая Android-оркестрация.
- `WebCaptureController` — instrumentation, page snapshots, JavaScript bridge и сбор chunk-данных.
- `WebResearchScripts` — JavaScript payloads для захвата browser-side событий.
- `WebResourceCapture` — копирование ресурсов и внешних scripts.
- `WebResearchWebViewController` — WebViewClient/WebChromeClient и маршрутизация событий WebView.
- `WebNavigationController` — URL normalization и navigation.
- `WebBookmarkController` — bookmarks.
- `WebCookieStatsController` — статистика cookies.
- `WebDownloadController` — обработка скачиваний, инициированных сайтом.
- `WebResearchExportController` — lifecycle ZIP-экспорта.
- `ResearchArchive` — raw capture state и построение экспортируемого архива.
- `NetworkRecordPipeline` — граница raw archive → debugger.
- `NetworkDebugStore` — correlated debugger storage.
- `NetworkEventCorrelator` — политика корреляции сетевых событий.
- `NetworkDisplayMerger` — display-only объединение записей.
- `NetworkEventClassifier` — классификация событий для debugger-а.
- `NetworkDebuggerDataSource` — синхронизация debugger UI с хранилищем.
- `NetworkDebuggerActivity` — основной UI анализа сетевых событий.
- `NetworkReplayController` — EDIT / REPLAY.
- `NetworkRequestActions` — вспомогательные действия над запросами, включая GET BODY/replay.
- `ResearchSecretRedactor` — редактирование чувствительных данных в производных представлениях.
- `ResultDelivery` — сохранение/шаринг подготовленных файлов.
- `WebUiTheme` и `AccentColorPickerView` — theme/accent UI.

## Исторический контекст

До текущей структуры проект проходил серию выделений ответственности из монолитных Activity/utility-классов: capture orchestration, resource capture, JavaScript payloads, replay, correlation, navigation/bookmarks/cookies, WebView orchestration, ZIP export и raw-to-debugger pipeline были последовательно вынесены в отдельные компоненты.

При последующих изменениях важнее сохранять текущие границы и инварианты, чем старую нумерацию промежуточных рефакторингов.
