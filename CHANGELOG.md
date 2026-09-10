# Changelog

История опубликованных релизов. Новые release-блоки добавляются автоматически сверху; ручной текст вне автоматически управляемых блоков сохраняется.

<!-- AUTO-CHANGELOG-INSERT -->
<!-- AUTO-CHANGELOG-v16-START -->
## v16 — 2026-09-10T01:54:40Z

- Release commit: `9af900940abfc3a791d34700a2407e6c330c9082`
- Artifact: `web-research-v16.apk`
- SHA-256: `b5562228e04a2c2de6b0daf3095937a5cc7602d169b225a016c2e616544d2539`
- Previous release: **v15**

### Changes

- refactor: split debugger projection and text formatting
- refactor: centralize shared cookie trace logic
- refactor: separate raw archive from export generation
- release: publish refactoring stages 2-4 [release]

### Changed files

- `app/src/main/java/ru/evrasia/research/CookieTraceProvider.kt`
- `app/src/main/java/ru/evrasia/research/CookieTraceSupport.kt`
- `app/src/main/java/ru/evrasia/research/CookieTraceUiProvider.kt`
- `app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt`
- `app/src/main/java/ru/evrasia/research/NetworkDebuggerProjection.kt`
- `app/src/main/java/ru/evrasia/research/NetworkDebuggerText.kt`
- `app/src/main/java/ru/evrasia/research/ResearchArchive.kt`
- `app/src/main/java/ru/evrasia/research/ResearchArchiveExporter.kt`
- `app/src/main/java/ru/evrasia/research/WebResearchExportController.kt`
<!-- AUTO-CHANGELOG-v16-END -->
<!-- AUTO-CHANGELOG-v15-START -->
## v15 — 2026-09-10T01:35:51Z

- Release commit: `5a552759abe27730dbca0a185d9834cf93943f33`
- Artifact: `web-research-v15.apk`
- SHA-256: `c23ac04647c6c90130b3ff205cf4682fd8bb9aaf835f15fbcc0f54e5e7af5893`
- Previous release: **v14**

### Changes

- refactor: remove unreachable legacy code
- release: publish dead-code cleanup [release]

### Changed files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt`
- `app/src/main/java/ru/evrasia/research/NetworkRequestActions.kt`
- `app/src/main/java/ru/evrasia/research/NetworkResearchActivity.kt`
- `app/src/main/java/ru/evrasia/research/ResearchSecretRedactor.kt`
- `app/src/main/java/ru/evrasia/research/WebCookieStatsController.kt`
- `app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt`
<!-- AUTO-CHANGELOG-v15-END -->
<!-- AUTO-CHANGELOG-v14-START -->
## v14 — 2026-09-10T01:07:37Z

- Release commit: `9f1c626114450c00669dbe0b90fa65812d5343eb`
- Artifact: `web-research-v14.apk`
- SHA-256: `330fe5fbd9d4c2036da752f18bf7828b542abb6aa7f0245f0f16e578378f585f`
- Previous release: **v13**

### Changes

- ci: recover standardized release documentation for v13
- docs: verify standardized release documentation automation [release]

### Changed files

- `.github/workflows/android-apk.yml`
<!-- AUTO-CHANGELOG-v14-END -->
<!-- AUTO-CHANGELOG-v13-START -->
## v13 — 2026-09-10T01:00:17Z

- Release commit: `e50ed17c141e9ed36ab8bc5a5180d0f4d0c3f1b0`
- Artifact: `web-research-v13.apk`
- SHA-256: `fabd7fffe97ebdb5e6640a98cbe6b041fedcadd9a3e8771cdedc3111029280c3`
- Previous release: **v12**

### Changes

- docs: standardize release documentation contract
- docs: accept standardized release documentation contract [release]

### Changed files

- `.github/scripts/update-release-docs.py`
- `.github/workflows/android-apk.yml`
- `.github/workflows/validate-work-branches.yml`
<!-- AUTO-CHANGELOG-v13-END -->
<!-- AUTO-CHANGELOG-v12-START -->
## v12 — 2026-09-10T00:39:13Z

- Release commit: `05fb86c6dca3abe1d895cf9c53da11bc7c1aa4af`
- Artifact: `web-research-v12.apk`
- SHA-256: `f755ab9c749525951635744d8a8a10ed260f83baf89297a076c34a39ac327ffb`
- Previous release: **v11**

### Changes

- docs: automate release documentation updates
- ci: refresh and maintain release workflow dependencies
- ci: accept maintained release workflow dependencies [release]

### Changed files

- `.github/actions/build-apk/action.yml`
- `.github/dependabot.yml`
- `.github/scripts/update-release-docs.py`
- `.github/workflows/_release-apk.yml`
- `.github/workflows/_release-core.yml`
- `.github/workflows/android-apk.yml`
- `.github/workflows/validate-work-branches.yml`
<!-- AUTO-CHANGELOG-v12-END -->
