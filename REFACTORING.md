# Refactoring

Документ описывает текущее архитектурное состояние `web-research` и ключевые инварианты, которые должны сохраняться при дальнейшем рефакторинге.

<!-- AUTO-RELEASE-START -->
## Состояние на v12

- Релизный commit: `05fb86c6dca3abe1d895cf9c53da11bc7c1aa4af`
- Предыдущая контрольная точка: **v11**
- APK: `web-research-v12.apk`
- SHA-256: `f755ab9c749525951635744d8a8a10ed260f83baf89297a076c34a39ac327ffb`

### Изменения между v11 и v12

- docs: automate release documentation updates
- ci: refresh and maintain release workflow dependencies
- ci: accept maintained release workflow dependencies [release]

### Затронутые файлы

- `.github/actions/build-apk/action.yml`
- `.github/dependabot.yml`
- `.github/scripts/update-release-docs.py`
- `.github/workflows/_release-apk.yml`
- `.github/workflows/_release-core.yml`
- `.github/workflows/android-apk.yml`
- `.github/workflows/validate-work-branches.yml`
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

## Текущая архитектура релиза

### L1 — универсальное ядро

`.github/workflows/_release-core.yml`

Отвечает за:
- release gate по `[release]`;
- определение следующего числового тега;
- публикацию GitHub Release;
- проверку уже опубликованного файла по SHA-256;
- idempotent recovery;
- rollback незавершённого draft/tag;
- очистку временных Actions artifacts после успешной публикации.

### L2 — универсальный APK-уровень

`.github/workflows/_release-apk.yml`

Отвечает за:
- режимы `release` и `validate`;
- получение unsigned APK candidate от L3;
- проверку package/version до подписи;
- проверку, что candidate действительно unsigned;
- architecture check;
- подпись постоянным ключом;
- проверку сертификата и финального APK;
- retention APK-бинарников;
- Telegram notification.

### L3 — проектная сборка

`.github/actions/build-apk/action.yml`

Отвечает только за проектно-специфичную сборку:

```text
./gradlew :app:assembleRelease
```

Номер релиза передаётся как `RELEASE_VERSION_CODE`.

## Точки входа

- `.github/workflows/android-apk.yml` — релиз из `main`.
- `.github/workflows/validate-work-branches.yml` — проверка `*-work` через тот же L2 в режиме `validate`.

## Автоматическая актуализация документации

После успешной публикации каждого релиза выполняется отдельный post-release job:

1. получает exact release metadata и SHA-256 опубликованного APK;
2. определяет предыдущий числовой тег;
3. собирает commit subjects и изменённые файлы между релизами;
4. обновляет управляемые блоки в `README.md` и `REFACTORING.md`;
5. делает отдельный docs commit без `[release]`.

Статические архитектурные разделы остаются человекочитаемыми и редактируемыми вручную; автоматически заменяются только блоки между `AUTO-RELEASE-START` и `AUTO-RELEASE-END`.

## Исторический контекст

До текущей структуры проект проходил серию выделений ответственности из монолитных Activity/utility-классов: capture orchestration, resource capture, JavaScript payloads, replay, correlation, navigation/bookmarks/cookies, WebView orchestration, ZIP export и raw-to-debugger pipeline были последовательно вынесены в отдельные компоненты.

При последующих изменениях важнее сохранять текущие границы и инварианты, чем старую нумерацию промежуточных рефакторингов.
