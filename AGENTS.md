# Agent guide — Rumb

Guidance for AI coding agents (Claude Code, opencode, or any other). `CLAUDE.md` imports this
file — edit here, never duplicate content there. Architecture overview and package map live in
`README.md`; this file holds only what you can't derive from the code.

## Golden rules

1. **Never edit vendored/contract files**: `app/src/main/assets/desktop/leaflet.js` and
   `leaflet.css` (third-party Leaflet); `app/src/main/aidl/btools/routingapp/IBRouterService.aidl`
   (mirrors BRouter's own interface — package name, class name and method signature are an
   external contract, do not rename or "fix" them).
2. **Never strip attribution KDoc headers.** `data/recording/` (TrackRecorder, RecordingService,
   PressureSource, ble/BleSensorManager) and `data/opentracks/` carry per-file Apache-2.0
   attributions to OpenTracks / OSMDashboard (see `NOTICE`). They are license obligations,
   not comments.
3. **Room schema changes follow a strict protocol** (see below). There is no destructive
   fallback and `exportSchema = false` — a missed migration crashes every existing install
   on upgrade.
4. **Every new user-facing string goes into all 18 locales** and passes
   `python3 scripts/check_i18n.py` (see i18n below).
5. **CI runs no tests** — the release workflow only builds. Run
   `./gradlew :app:testDebugUnitTest` yourself before considering work done.

## Build, test, release

- **JDK 17** required (`JAVA_HOME`), compileSdk/targetSdk 35, minSdk 26. Kotlin 2.0, AGP 8.7,
  Compose (BOM), KSP. Version catalog in `gradle/libs.versions.toml`.
- Build: `./gradlew :app:assembleDebug` · Tests: `./gradlew :app:testDebugUnitTest`
  (JUnit 5 + MockK + AssertJ + coroutines-test; ~55 files under `app/src/test/`; there is NO
  `androidTest/` — hard logic is deliberately extracted into pure JVM-testable classes; keep
  doing that: new logic goes in a pure class + unit test, not inside an Activity/Service).
- i18n check after touching any `res/values*/strings_*.xml`: `python3 scripts/check_i18n.py`
  (exit 1 = missing keys or broken printf specifiers in some locale).
- **Release**: bump `versionCode` + `versionName` in `app/build.gradle.kts`, commit, tag
  `vX.Y.Z`, push tag → `.github/workflows/release.yml` builds the signed APK and publishes a
  GitHub Release with generated notes. Local `assembleRelease` falls back to the **debug** key
  (real keystore only exists in CI via env secrets) — a locally built release APK will not
  install over a published one.
- Minify is OFF even in release; no flavors.

## Conventions

- **Commits**: English, `Module: description` (e.g. `Sensors: find HR straps that don't
  advertise 0x180D`, `Fix: back from the viewer exits to the launcher`). Modules seen:
  Viewer, Maps, Endurain, Scale, Sensors, Laps, Competitions, Editors, Fix.
- **Three-language split — keep it**:
  - Code, KDoc, comments: **English**.
  - `DebugLog` messages and internal exception texts: **Catalan** (`"BLE: sense permís"`).
  - User-facing strings: **always resources**, never hardcoded. Base `values/` is **English**;
    17 translation folders (`values-ca`, `-es`, `-fr`, …). Strings are split per module
    (`strings_home.xml`, `strings_scale.xml`, …) — add to the matching file in every locale.
  - Desktop web app (`assets/desktop/i18n.js`): **Catalan is the reference copy** there,
    en/es are translations. `i18n.js` must load before `app.js`.
- **DI**: manual service locator in `RumbApplication` (lazy singletons, `RumbApplication.from(ctx)`).
  Deliberate — do not introduce Hilt/Koin.
- **Prefs**: plain `SharedPreferences` (named files), not DataStore, even though the dependency
  exists. Follow the existing pattern.
- **Background work**: WorkManager for anything deferrable (upload, download, backfill);
  coroutines elsewhere. `RecordingService` is a started (not bound) foreground service that
  publishes state through the singleton `MutableStateFlow` in `NativeRecording`
  (`data/recording/RecordingState.kt`); `null` = idle. Control it via intent actions
  (`ACTION_START/PAUSE/RESUME/STOP/…`), never by binding.

## Room migration protocol

`RumbDatabase` lives in `data/tracks/FollowTrack.kt` ("rumb.db", currently **version 12**).
Any entity/schema change requires, in the same commit:
1. Bump `version` in the `@Database` annotation.
2. Hand-write `MIGRATION_x_y` next to the existing ones in `FollowTrack.kt`.
3. Register it in `RumbApplication`'s `addMigrations(...)` chain.

Quirks to respect: competition/circuit unification (v11) left orphaned competition columns in
`follow_tracks` (harmless — leave them); circuit ids are offset by `CIRCUIT_ID_OFFSET =
1_000_000_000L` to avoid collisions.

## Fragile zones (easy to break, hard to notice)

- **BLE GATT is one-operation-at-a-time** (`ble/BleSensorManager`): CCC descriptors are written
  serially via the `pendingWrites` queue, each from the previous `onDescriptorWrite`. Writing
  them in a loop silently drops all but the first.
- **BLE discovery scans must be UNFILTERED**: many HR devices (Mi Band, Amazfit, watches) do
  not advertise service UUID 0x180D — a `ScanFilter.setServiceUuid` filter makes them
  invisible. Filter client-side instead (see `SensorsScreen` and `BleSensorProbe`).
- **`ViewerPreferences` stores some Doubles as raw long bits** (`toRawBits()`) because
  SharedPreferences has no Double type (circuit lat/lng/radius). Do not "simplify" to Float —
  it loses coordinate precision.
- **HUD and Dades layouts are JSON blobs in prefs** (`hudLayoutJson`, `dataLayoutJson`).
  Changing `HudLayout`/`DataLayout` shapes needs an in-code migration for old blobs
  (see the existing one in `DataLayout.kt`).
- **Wire formats that must stay in sync by hand**:
  - `data/desktop/DesktopDto` ↔ `assets/desktop/app.js` (LAN web app JSON).
  - `data/endurain/EndurainApi` DTOs ↔ the Endurain REST server (`X-API-Key` mode can ONLY
    upload; JWT mode uses `Authorization: Bearer` + `X-Client-Type: mobile`; upload is
    multipart part `"file"`).
  - `scale/ble/MiScaleParser`: fixed 13-byte little-endian MIBCS2 frame; impedance valid only
    with status bit set and value ∉ {0, 0xFFFF}. S400 is not supported.
  - `data/opentracks/model/Model.kt`: OpenTracks Dashboard API contract — render-agnostic and
    JVM-testable on purpose; keep MapLibre types out of it.
- **`DesktopServer` keeps multiple session tokens valid simultaneously** — collapsing to a
  single token field would evict every other connected browser.
- **Networking etiquette baked into the app**: all MapLibre tile requests need the custom
  `Rumb/<version>` User-Agent set in `RumbApplication` (OSM returns 403 otherwise);
  `NominatimClient` enforces OSM's 1 req/s policy. Don't remove either.
- **The `scale/` module is deliberately self-contained** (one repository line in
  `RumbApplication`, one DAO in `RumbDatabase`, marked with "remove this line" comments).
  Keep new scale features inside the module; additive integrations only.
