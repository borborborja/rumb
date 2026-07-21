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
- **Release**: follow `DEPLOY.md` (single source of truth for the process — update it in the
  same commit if the process changes). Short version: bump `versionCode` + `versionName`,
  commit, tag `vX.Y.Z`, push tag → CI builds the signed APK and publishes the GitHub Release.
  Local `assembleRelease` falls back to the **debug** key (real keystore only exists in CI) —
  a locally built release APK will not install over a published one.
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

## Checks before calling a task done

Match the checks to what you touched; run them yourself — CI won't. Never claim something
works without having run the check; if you couldn't run it (no JDK/SDK on the machine, needs
a physical device), say so explicitly in your report instead of implying success.

**Always (any code change):**
1. `./gradlew :app:testDebugUnitTest` — full unit suite, not just "it compiles".
2. If you added logic, you added a test for it (pure class + JUnit 5 test in `app/src/test/`).
   Logic without a test is an unfinished task, not a done one.
3. `git diff` review: no vendored files touched, no attribution KDoc removed, no stray files
   (only content changes you intended — beware mode-only noise).

**If you touched…**
- **Any `strings_*.xml`** → `python3 scripts/check_i18n.py` must exit 0 (all 18 locales,
  matching printf specifiers).
- **A Room entity/DAO/schema** → verify the 3-step migration protocol below is complete in the
  same commit, and add a unit test exercising the new column/query if feasible. Think about
  an existing install upgrading: does every path from v_old to v_new have a migration?
- **Serialized shapes** (`HudLayout`, `DataLayout`, prefs JSON blobs, `DesktopDto`) → confirm
  old persisted data still decodes (in-code migration or `ignoreUnknownKeys` covers it) and
  update the counterpart (`assets/desktop/app.js` for `DesktopDto`).
- **BLE, sensors, scale, GPS, TTS, or anything hardware-bound** → JVM tests can only cover the
  parsers/engines. Extract and test those; for the rest, state plainly that it needs a manual
  test on a device and what to test (e.g. "pair the strap from Sensors → Buscar").
- **The desktop web app** (`assets/desktop/`) → it's plain JS with no build step or linter:
  re-read your diff carefully (syntax errors ship as-is) and keep `i18n.js` keys in sync
  across ca/en/es (Catalan is the reference).
- **Networking** (tiles, Nominatim, Endurain) → confirm User-Agent / rate-limit etiquette
  survived your change; DTO changes match the real server API, not an assumed one.
- **`versionCode`/`versionName` or the release workflow** → remember: tests don't run in CI;
  a broken build only surfaces when the tag build fails. Compile before tagging.

**Reporting**: state what you ran and its actual result ("tests: 55 passed", "i18n OK ·
806 keys"). If something failed or was skipped, lead with that — a wrong "all good" costs
more than an honest "unverified".

## Room migration protocol

`RumbDatabase` lives in `data/tracks/FollowTrack.kt` ("rumb.db", currently **version 12**).
Any entity/schema change requires, in the same commit:
1. Bump `version` in the `@Database` annotation.
2. Hand-write `MIGRATION_x_y` next to the existing ones in `FollowTrack.kt`.
3. Register it in `RumbApplication`'s `addMigrations(...)` chain.

```kotlin
// FollowTrack.kt — @Database(version = 13, …) plus, next to its siblings:
val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE follow_tracks ADD COLUMN foo INTEGER NOT NULL DEFAULT 0")
    }
}
// RumbApplication.kt — append RumbDatabase.MIGRATION_12_13 to addMigrations(…)
```

Quirks to respect: competition/circuit unification (v11) left orphaned competition columns in
`follow_tracks` (harmless — leave them); circuit ids are offset by `CIRCUIT_ID_OFFSET =
1_000_000_000L` to avoid collisions.

## Fragile zones (easy to break, hard to notice)

- **BLE GATT is one-operation-at-a-time** (`ble/BleSensorManager`): CCC descriptors are written
  serially via the `pendingWrites` queue, each from the previous `onDescriptorWrite`. Writing
  them in a loop silently drops all but the first.

  ```kotlin
  // WRONG — GATT silently drops every write after the first:
  descriptors.forEach { gatt.writeDescriptor(it, ENABLE_NOTIFICATION_VALUE) }
  // RIGHT — queue them; each next write fires from onDescriptorWrite():
  pendingWrites[gatt] = ArrayDeque(descriptors)
  writeNextDescriptor(gatt)
  ```
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
