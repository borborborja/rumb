package cat.rumb.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight synchronous preferences for the viewer (needs a value at Activity onCreate).
 * The management UI writes these; the viewer reads them. Backed by SharedPreferences.
 */
class ViewerPreferences private constructor(private val prefs: SharedPreferences) {

    var baseMapId: String?
        get() = prefs.getString(KEY_BASE_MAP, null)
        set(value) = prefs.edit().putString(KEY_BASE_MAP, value).apply()

    /** JSON blob describing the active HUD layout (see hud.HudLayout). */
    var hudLayoutJson: String?
        get() = prefs.getString(KEY_HUD_LAYOUT, null)
        set(value) = prefs.edit().putString(KEY_HUD_LAYOUT, value).apply()

    /** JSON blob describing the "Dades" screen layout (see data.DataLayout). */
    var dataLayoutJson: String?
        get() = prefs.getString(KEY_DATA_LAYOUT, null)
        set(value) = prefs.edit().putString(KEY_DATA_LAYOUT, value).apply()

    // --- Display units (see hud.Units) ---
    /** DistanceUnit name: "KM" or "MILE". */
    var distanceUnit: String
        get() = prefs.getString(KEY_UNIT_DISTANCE, "KM") ?: "KM"
        set(value) = prefs.edit().putString(KEY_UNIT_DISTANCE, value).apply()

    /** ElevationUnit name: "METER" or "FOOT". */
    var elevationUnit: String
        get() = prefs.getString(KEY_UNIT_ELEVATION, "METER") ?: "METER"
        set(value) = prefs.edit().putString(KEY_UNIT_ELEVATION, value).apply()

    /** SpeedUnit name: "KMH" or "MPH". */
    var speedUnit: String
        get() = prefs.getString(KEY_UNIT_SPEED, "KMH") ?: "KMH"
        set(value) = prefs.edit().putString(KEY_UNIT_SPEED, value).apply()

    /** Id of the track the user has chosen to follow, or null. */
    var activeFollowTrackId: Long
        get() = prefs.getLong(KEY_FOLLOW_TRACK, -1L)
        set(value) = prefs.edit().putLong(KEY_FOLLOW_TRACK, value).apply()

    // --- Viewer options (in-viewer quick settings) ---
    /** Map orientation: "NORTH_UP" (fixed) or "HEADING_UP" (rotates to travel direction). */
    var mapOrientation: String
        get() = prefs.getString(KEY_MAP_ORIENTATION, "NORTH_UP") ?: "NORTH_UP"
        set(value) = prefs.edit().putString(KEY_MAP_ORIENTATION, value).apply()

    /** Keep the screen on in the viewer (independent of OpenTracks' own setting). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Hide the system bars (immersive) in the viewer. */
    var fullscreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    /** Auto-zoom the map in near a route turn/junction and out on straights (while following). */
    var adaptiveZoom: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_ZOOM, false)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_ZOOM, value).apply()

    // --- Native recording engine ---
    var recGpsIntervalSec: Int
        get() = prefs.getInt(KEY_REC_GPS_INTERVAL, 1)
        set(value) = prefs.edit().putInt(KEY_REC_GPS_INTERVAL, value).apply()

    var recMinDistanceM: Float
        get() = prefs.getFloat(KEY_REC_MIN_DISTANCE, 3f)
        set(value) = prefs.edit().putFloat(KEY_REC_MIN_DISTANCE, value).apply()

    var recMaxAccuracyM: Float
        get() = prefs.getFloat(KEY_REC_MAX_ACCURACY, 25f)
        set(value) = prefs.edit().putFloat(KEY_REC_MAX_ACCURACY, value).apply()

    var recAutoPause: Boolean
        get() = prefs.getBoolean(KEY_REC_AUTO_PAUSE, false)
        set(value) = prefs.edit().putBoolean(KEY_REC_AUTO_PAUSE, value).apply()

    /** Whether the lap (mark/end-lap) buttons show next to the record control in both views. */
    var lapManagementEnabled: Boolean
        get() = prefs.getBoolean(KEY_LAP_MANAGEMENT, true)
        set(value) = prefs.edit().putBoolean(KEY_LAP_MANAGEMENT, value).apply()

    /** Position auto-lap: the "start laps" spot becomes a start/finish line; each crossing auto-splits. */
    var autoLapByPosition: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAP_BY_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LAP_BY_POSITION, value).apply()

    /** Find the circuit from your movement and open laps at it — no flag press, no meta at the start. */
    var autoDetectLoop: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_LOOP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT_LOOP, value).apply()

    // Transient circuit-mode config, set by the viewer before starting a circuit recording and read
    // by RecordingService.configFrom. Doubles are stored via raw long bits (SharedPreferences lacks
    // Double) to keep lat/lng precision. Cleared on save/stop.
    var circuitActive: Boolean
        get() = prefs.getBoolean(KEY_CIRCUIT_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_CIRCUIT_ACTIVE, value).apply()
    var circuitLineLat: Double
        get() = Double.fromBits(prefs.getLong(KEY_CIRCUIT_LAT, 0L))
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_LAT, value.toRawBits()).apply()
    var circuitLineLng: Double
        get() = Double.fromBits(prefs.getLong(KEY_CIRCUIT_LNG, 0L))
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_LNG, value.toRawBits()).apply()
    var circuitRadiusM: Double
        get() = Double.fromBits(prefs.getLong(KEY_CIRCUIT_RADIUS, (25.0).toRawBits()))
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_RADIUS, value.toRawBits()).apply()
    var circuitMinLapMs: Long
        get() = prefs.getLong(KEY_CIRCUIT_MIN_MS, 20_000L)
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_MIN_MS, value).apply()
    var circuitMinLapM: Double
        get() = Double.fromBits(prefs.getLong(KEY_CIRCUIT_MIN_M, (100.0).toRawBits()))
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_MIN_M, value.toRawBits()).apply()
    /** Length of the competition's reference lap (m); 0 = ad-hoc circuit, no lap to compare against. */
    var circuitRefDistanceM: Double
        get() = Double.fromBits(prefs.getLong(KEY_CIRCUIT_REF_M, (0.0).toRawBits()))
        set(value) = prefs.edit().putLong(KEY_CIRCUIT_REF_M, value.toRawBits()).apply()

    var recBarometer: Boolean
        get() = prefs.getBoolean(KEY_REC_BAROMETER, true)
        set(value) = prefs.edit().putBoolean(KEY_REC_BAROMETER, value).apply()

    /** Route-manager view mode: "LIST", "DETAILED" or "TILES". */
    var routeViewMode: String
        get() = prefs.getString(KEY_ROUTE_VIEW_MODE, "DETAILED") ?: "DETAILED"
        set(value) = prefs.edit().putString(KEY_ROUTE_VIEW_MODE, value).apply()

    /** User-created (possibly empty) folders for trainings. */
    var foldersTraining: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS_TRAINING, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FOLDERS_TRAINING, value).apply()

    /** User-created (possibly empty) folders for routes to follow. */
    var foldersRoute: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS_ROUTE, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FOLDERS_ROUTE, value).apply()

    /** JSON list of user-defined activity types (see tracks.CustomActivityType). */
    var customActivityTypesJson: String?
        get() = prefs.getString(KEY_CUSTOM_ACTIVITY_TYPES, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_ACTIVITY_TYPES, value).apply()

    /** TrackSort name for the trainings tab. */
    var trackSortTraining: String
        get() = prefs.getString(KEY_SORT_TRAINING, "DATE_DESC") ?: "DATE_DESC"
        set(value) = prefs.edit().putString(KEY_SORT_TRAINING, value).apply()

    /** TrackSort name for the routes tab. */
    var trackSortRoute: String
        get() = prefs.getString(KEY_SORT_ROUTE, "DATE_DESC") ?: "DATE_DESC"
        set(value) = prefs.edit().putString(KEY_SORT_ROUTE, value).apply()

    /** Active activity-type filter (type id) for the trainings tab; null = all. */
    var trackFilterTypeTraining: String?
        get() = prefs.getString(KEY_FILTER_TYPE_TRAINING, null)
        set(value) = prefs.edit().putString(KEY_FILTER_TYPE_TRAINING, value).apply()

    /** Active activity-type filter (type id) for the routes tab; null = all. */
    var trackFilterTypeRoute: String?
        get() = prefs.getString(KEY_FILTER_TYPE_ROUTE, null)
        set(value) = prefs.edit().putString(KEY_FILTER_TYPE_ROUTE, value).apply()

    /** Base map for the training statistics screen (MapSource id); null = default. */
    var statsMapSourceId: String?
        get() = prefs.getString(KEY_STATS_MAP_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_STATS_MAP_SOURCE, value).apply()

    /** Track paint on the training statistics map: "SOLID", "ALTITUDE", "HR" or "SPEED". */
    var statsTrackPaint: String
        get() = prefs.getString(KEY_STATS_TRACK_PAINT, "SOLID") ?: "SOLID"
        set(value) = prefs.edit().putString(KEY_STATS_TRACK_PAINT, value).apply()

    /** Competition mode: screen-edge halo colored by the ghost race state. */
    var competitionHalo: Boolean
        get() = prefs.getBoolean(KEY_COMPETITION_HALO, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPETITION_HALO, value).apply()

    /** Competition mode: show the estimated seconds gap under the meters. */
    var competitionShowSeconds: Boolean
        get() = prefs.getBoolean(KEY_COMPETITION_SECONDS, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPETITION_SECONDS, value).apply()

    /** Auto-pause trigger: seconds standing still before the recording pauses itself. */
    var recAutoPauseSec: Int
        get() = prefs.getInt(KEY_REC_AUTO_PAUSE_SEC, 5)
        set(value) = prefs.edit().putInt(KEY_REC_AUTO_PAUSE_SEC, value).apply()

    /** Fullscreen 3-2-1 countdown before a recording starts (after the GPS precision gate). */
    var recCountdown: Boolean
        get() = prefs.getBoolean(KEY_REC_COUNTDOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_REC_COUNTDOWN, value).apply()

    /** Port for the desktop-mode embedded web server (falls forward if busy). */
    var desktopServerPort: Int
        get() = prefs.getInt(KEY_DESKTOP_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_DESKTOP_PORT, value).apply()

    /** Voice/beep warnings before route turns while following. */
    var turnVoice: Boolean
        get() = prefs.getBoolean(KEY_TURN_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_TURN_VOICE, value).apply()

    // --- Sport mode ---
    /** Sport chosen for the next/current recording (an ActivityTypes id). Sticky across sessions. */
    var activeSportId: String?
        get() = prefs.getString(KEY_ACTIVE_SPORT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_SPORT, value).apply()

    /** True once the pre-sport global HUD layout has been adopted by a sport (see HudLayoutStore). */
    var hudLayoutAdopted: Boolean
        get() = prefs.getBoolean(KEY_HUD_ADOPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_ADOPTED, value).apply()

    /** Per-sport HUD layout JSON (null = never edited for that sport). */
    fun hudLayoutJsonFor(sportId: String): String? = prefs.getString(KEY_HUD_LAYOUT + "_" + sportId, null)
    fun setHudLayoutJsonFor(sportId: String, raw: String?) =
        prefs.edit().putString(KEY_HUD_LAYOUT + "_" + sportId, raw).apply()

    /** True once the pre-sport global Dades layout has been adopted by a sport (see DataLayoutStore). */
    var dataLayoutAdopted: Boolean
        get() = prefs.getBoolean(KEY_DATA_ADOPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DATA_ADOPTED, value).apply()

    /** Per-sport Dades layout JSON (null = never edited for that sport). */
    fun dataLayoutJsonFor(sportId: String): String? = prefs.getString(KEY_DATA_LAYOUT + "_" + sportId, null)
    fun setDataLayoutJsonFor(sportId: String, raw: String?) =
        prefs.edit().putString(KEY_DATA_LAYOUT + "_" + sportId, raw).apply()

    /** Per-base-map display config JSON (null = never edited → the map looks as it always did). */
    fun mapDisplayJsonFor(sourceId: String): String? = prefs.getString(KEY_MAP_DISPLAY + "_" + sourceId, null)
    fun setMapDisplayJsonFor(sourceId: String, raw: String?) =
        prefs.edit().putString(KEY_MAP_DISPLAY + "_" + sourceId, raw).apply()

    /**
     * Per-provider tile API key (null = not set → the keyed map stays disabled). Stored only after
     * a successful verification, so a stored key means "verified and active". Plaintext, like the
     * Endurain upload key: a tile key ends up in the request URL and is not a high-value secret.
     */
    fun mapApiKeyFor(provider: String): String? =
        prefs.getString(KEY_MAP_API_KEY + "_" + provider, null)?.takeIf { it.isNotBlank() }
    fun setMapApiKeyFor(provider: String, key: String?) =
        prefs.edit().putString(KEY_MAP_API_KEY + "_" + provider, key?.takeIf { it.isNotBlank() }).apply()

    /** Per-sport split distance (m): 1 km for running, usually off for cycling. */
    fun autoLapEveryMFor(sportId: String?): Float =
        if (sportId == null) autoLapEveryM else prefs.getFloat(KEY_AUTO_LAP_EVERY_M + "_" + sportId, autoLapEveryM)
    fun setAutoLapEveryMFor(sportId: String?, meters: Float) {
        if (sportId == null) autoLapEveryM = meters
        else prefs.edit().putFloat(KEY_AUTO_LAP_EVERY_M + "_" + sportId, meters).apply()
    }

    /** Id of the last APK download whose installer we already offered (see SettingsScreen). */
    var lastInstalledDownloadId: String?
        get() = prefs.getString(KEY_LAST_INSTALL_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_INSTALL_ID, value).apply()

    /** Auto-lap every N metres (runner splits). 0 = off. */
    var autoLapEveryM: Float
        get() = prefs.getFloat(KEY_AUTO_LAP_EVERY_M, 0f)
        set(value) = prefs.edit().putFloat(KEY_AUTO_LAP_EVERY_M, value).apply()

    /** Debug: replay this track as fake GPS instead of the real one (-1 = off). */
    var simulateTrackId: Long
        get() = prefs.getLong(KEY_SIM_TRACK, -1L)
        set(value) = prefs.edit().putLong(KEY_SIM_TRACK, value).apply()

    /** Debug: how much faster than real time the replay runs. */
    var simulateSpeed: Float
        get() = prefs.getFloat(KEY_SIM_SPEED, 5f)
        set(value) = prefs.edit().putFloat(KEY_SIM_SPEED, value).apply()

    /** 3-2-1 countdown as you close in on a circuit's finish line, landing on the new lap. */
    var lapCountdown: Boolean
        get() = prefs.getBoolean(KEY_LAP_COUNTDOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_LAP_COUNTDOWN, value).apply()

    /**
     * Race a ghost whenever there is a reference to chase. Decided once here rather than asked
     * mid-lap: the prompt it replaces popped up while crossing the line, the worst possible moment,
     * and it could only appear on the 2nd lap — a competition's ghost is ready from the first one.
     */
    var ghostEnabled: Boolean
        get() = prefs.getBoolean(KEY_GHOST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GHOST_ENABLED, value).apply()

    /** Name of the selected [cat.rumb.app.data.competition.GhostSource]. */
    var ghostSource: String?
        get() = prefs.getString(KEY_GHOST_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_GHOST_SOURCE, value).apply()

    /** User's weight (kg), drives the MET calorie estimate. */
    var userWeightKg: Int
        get() = prefs.getInt(KEY_USER_WEIGHT, 75)
        set(value) = prefs.edit().putInt(KEY_USER_WEIGHT, value).apply()

    /** User's maximum heart rate (bpm), drives the competition HR-zone analysis. */
    var userMaxHr: Int
        get() = prefs.getInt(KEY_USER_MAX_HR, 190)
        set(value) = prefs.edit().putInt(KEY_USER_MAX_HR, value).apply()

    /** User's age (years); 0 = unknown. With sex, enables the HR-based calorie estimate. */
    var userAge: Int
        get() = prefs.getInt(KEY_USER_AGE, 0)
        set(value) = prefs.edit().putInt(KEY_USER_AGE, value).apply()

    /** "M", "F" or "" (unknown). With age, enables the HR-based calorie estimate. */
    var userSex: String
        get() = prefs.getString(KEY_USER_SEX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_SEX, value).apply()

    /**
     * MAC addresses of paired BLE fitness sensors (heart rate / cadence / power). The addresses are
     * what the recording service connects to; [bleSensorsJson] holds the rest (name, warn flag) and
     * keeps this in sync. Kept as the source of truth for connecting so pairings survive downgrades.
     */
    var bleSensorAddrs: Set<String>
        get() = prefs.getStringSet(KEY_BLE_SENSORS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLE_SENSORS, value).apply()

    /** JSON list of paired sensors with their names and per-sensor settings (see ble.SavedSensor). */
    var bleSensorsJson: String?
        get() = prefs.getString(KEY_BLE_SENSORS_JSON, null)
        set(value) = prefs.edit().putString(KEY_BLE_SENSORS_JSON, value).apply()

    /** Name of the selected [cat.rumb.app.data.map.TrackColorMode]. */
    var trackColorMode: String?
        get() = prefs.getString(KEY_TRACK_COLOR_MODE, null)
        set(value) = prefs.edit().putString(KEY_TRACK_COLOR_MODE, value).apply()

    /** Color (hex "#RRGGBB") used in SINGLE mode. */
    var trackColor: String
        get() = prefs.getString(KEY_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        set(value) = prefs.edit().putString(KEY_TRACK_COLOR, value).apply()

    // --- Followed-route appearance ---
    var followColor: String
        get() = prefs.getString(KEY_FOLLOW_COLOR, DEFAULT_FOLLOW_COLOR) ?: DEFAULT_FOLLOW_COLOR
        set(value) = prefs.edit().putString(KEY_FOLLOW_COLOR, value).apply()

    var followWidth: Float
        get() = prefs.getFloat(KEY_FOLLOW_WIDTH, 6f)
        set(value) = prefs.edit().putFloat(KEY_FOLLOW_WIDTH, value).apply()

    /** Direction chevrons on the followed route. Only ever drawn while competing. */
    var followArrows: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_ARROWS, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_ARROWS, value).apply()

    var followArrowColor: String
        get() = prefs.getString(KEY_FOLLOW_ARROW_COLOR, DEFAULT_FOLLOW_COLOR) ?: DEFAULT_FOLLOW_COLOR
        set(value) = prefs.edit().putString(KEY_FOLLOW_ARROW_COLOR, value).apply()

    var followArrowSize: Float
        get() = prefs.getFloat(KEY_FOLLOW_ARROW_SIZE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_FOLLOW_ARROW_SIZE, value).apply()

    var followProgress: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_PROGRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_PROGRESS, value).apply()

    // --- Tracking point (the user's position marker): DOT | ARROW, color, size scale ---
    var trackingPointStyle: String
        get() = prefs.getString(KEY_TRACKING_STYLE, "DOT") ?: "DOT"
        set(value) = prefs.edit().putString(KEY_TRACKING_STYLE, value).apply()

    var trackingPointColor: String
        get() = prefs.getString(KEY_TRACKING_COLOR, DEFAULT_FOLLOW_COLOR) ?: DEFAULT_FOLLOW_COLOR
        set(value) = prefs.edit().putString(KEY_TRACKING_COLOR, value).apply()

    var trackingPointSize: Float
        get() = prefs.getFloat(KEY_TRACKING_SIZE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TRACKING_SIZE, value).apply()

    // --- Ghost marker (the opponent): DOT | GHOST, color, size scale. How it LOOKS — which lap it
    // replays is ghostSource, and whether it exists at all is ghostEnabled. ---
    var ghostMarkerStyle: String
        get() = prefs.getString(KEY_GHOST_MARKER_STYLE, "DOT") ?: "DOT"
        set(value) = prefs.edit().putString(KEY_GHOST_MARKER_STYLE, value).apply()

    var ghostColor: String
        get() = prefs.getString(KEY_GHOST_COLOR, DEFAULT_GHOST_COLOR) ?: DEFAULT_GHOST_COLOR
        set(value) = prefs.edit().putString(KEY_GHOST_COLOR, value).apply()

    var ghostSize: Float
        get() = prefs.getFloat(KEY_GHOST_SIZE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_GHOST_SIZE, value).apply()

    // --- Online map cache (MapLibre ambient cache) + route prefetch ---
    var mapCacheSizeMb: Int
        get() = prefs.getInt(KEY_MAP_CACHE_MB, 200)
        set(value) = prefs.edit().putInt(KEY_MAP_CACHE_MB, value).apply()

    var prefetchOnFollow: Boolean
        get() = prefs.getBoolean(KEY_PREFETCH_ON_FOLLOW, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFETCH_ON_FOLLOW, value).apply()

    // --- Off-route alert ---
    var offRouteThresholdM: Int
        get() = prefs.getInt(KEY_OFFROUTE_THRESHOLD, 40)
        set(value) = prefs.edit().putInt(KEY_OFFROUTE_THRESHOLD, value).apply()

    var offRouteSound: Boolean
        get() = prefs.getBoolean(KEY_OFFROUTE_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFROUTE_SOUND, value).apply()

    var offRouteVibrate: Boolean
        get() = prefs.getBoolean(KEY_OFFROUTE_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFROUTE_VIBRATE, value).apply()

    // --- Audio announcements ---
    var announceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ANN_ENABLED, value).apply()

    /** "VOICE" or "BEEP". */
    var announceMode: String
        get() = prefs.getString(KEY_ANN_MODE, "VOICE") ?: "VOICE"
        set(value) = prefs.edit().putString(KEY_ANN_MODE, value).apply()

    var announceLang: String
        get() = prefs.getString(KEY_ANN_LANG, "ca") ?: "ca"
        set(value) = prefs.edit().putString(KEY_ANN_LANG, value).apply()

    /** Beep-mode tone index (see BeepSound); default 1 = DOUBLE (previous behaviour). */
    var announceBeepSound: Int
        get() = prefs.getInt(KEY_ANN_BEEP_SOUND, 1)
        set(value) = prefs.edit().putInt(KEY_ANN_BEEP_SOUND, value).apply()

    /** Play the ~150 m heads-up turn warning (in addition to the at-turn one). */
    var turnHeadsUp: Boolean
        get() = prefs.getBoolean(KEY_TURN_HEADS_UP, true)
        set(value) = prefs.edit().putBoolean(KEY_TURN_HEADS_UP, value).apply()

    var announceByDistance: Boolean
        get() = prefs.getBoolean(KEY_ANN_BY_DIST, true)
        set(value) = prefs.edit().putBoolean(KEY_ANN_BY_DIST, value).apply()

    var announceDistanceKm: Float
        get() = prefs.getFloat(KEY_ANN_DIST_KM, 1f)
        set(value) = prefs.edit().putFloat(KEY_ANN_DIST_KM, value).apply()

    var announceByTime: Boolean
        get() = prefs.getBoolean(KEY_ANN_BY_TIME, false)
        set(value) = prefs.edit().putBoolean(KEY_ANN_BY_TIME, value).apply()

    var announceTimeMin: Int
        get() = prefs.getInt(KEY_ANN_TIME_MIN, 10)
        set(value) = prefs.edit().putInt(KEY_ANN_TIME_MIN, value).apply()

    var annDistanceTime: Boolean
        get() = prefs.getBoolean(KEY_ANN_F_DIST, true)
        set(value) = prefs.edit().putBoolean(KEY_ANN_F_DIST, value).apply()

    var annPace: Boolean
        get() = prefs.getBoolean(KEY_ANN_F_PACE, true)
        set(value) = prefs.edit().putBoolean(KEY_ANN_F_PACE, value).apply()

    var annSplitPace: Boolean
        get() = prefs.getBoolean(KEY_ANN_F_SPLIT, false)
        set(value) = prefs.edit().putBoolean(KEY_ANN_F_SPLIT, value).apply()

    var annElevation: Boolean
        get() = prefs.getBoolean(KEY_ANN_F_ELEV, false)
        set(value) = prefs.edit().putBoolean(KEY_ANN_F_ELEV, value).apply()

    var annHeartRate: Boolean
        get() = prefs.getBoolean(KEY_ANN_F_HR, false)
        set(value) = prefs.edit().putBoolean(KEY_ANN_F_HR, value).apply()

    var offRouteSpoken: Boolean
        get() = prefs.getBoolean(KEY_OFFROUTE_SPOKEN, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFROUTE_SPOKEN, value).apply()

    companion object {
        private const val KEY_BASE_MAP = "base_map_id"
        private const val KEY_HUD_LAYOUT = "hud_layout_json"
        private const val KEY_MAP_DISPLAY = "map_display_json"
        private const val KEY_MAP_API_KEY = "map_api_key"
        private const val KEY_DATA_ADOPTED = "data_layout_adopted"
        private const val KEY_DATA_LAYOUT = "data_layout_json"
        private const val KEY_UNIT_DISTANCE = "unit_distance"
        private const val KEY_UNIT_ELEVATION = "unit_elevation"
        private const val KEY_UNIT_SPEED = "unit_speed"
        private const val KEY_MAP_ORIENTATION = "map_orientation"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_ADAPTIVE_ZOOM = "adaptive_zoom"
        private const val KEY_REC_GPS_INTERVAL = "rec_gps_interval"
        private const val KEY_REC_MIN_DISTANCE = "rec_min_distance"
        private const val KEY_REC_MAX_ACCURACY = "rec_max_accuracy"
        private const val KEY_REC_AUTO_PAUSE = "rec_auto_pause"
        private const val KEY_LAP_MANAGEMENT = "lap_management"
        private const val KEY_AUTO_LAP_BY_POSITION = "auto_lap_by_position"
        private const val KEY_AUTO_DETECT_LOOP = "auto_detect_loop"
        private const val KEY_CIRCUIT_ACTIVE = "circuit_active"
        private const val KEY_CIRCUIT_LAT = "circuit_line_lat"
        private const val KEY_CIRCUIT_LNG = "circuit_line_lng"
        private const val KEY_CIRCUIT_RADIUS = "circuit_radius_m"
        private const val KEY_CIRCUIT_MIN_MS = "circuit_min_lap_ms"
        private const val KEY_CIRCUIT_MIN_M = "circuit_min_lap_m"
        private const val KEY_CIRCUIT_REF_M = "circuit_ref_distance_m"
        private const val KEY_REC_BAROMETER = "rec_barometer"
        private const val KEY_BLE_SENSORS = "ble_sensors"
        private const val KEY_BLE_SENSORS_JSON = "ble_sensors_json"
        private const val KEY_ROUTE_VIEW_MODE = "route_view_mode"
        private const val KEY_FOLDERS_TRAINING = "folders_training"
        private const val KEY_FOLDERS_ROUTE = "folders_route"
        private const val KEY_CUSTOM_ACTIVITY_TYPES = "custom_activity_types"
        private const val KEY_SORT_TRAINING = "track_sort_training"
        private const val KEY_SORT_ROUTE = "track_sort_route"
        private const val KEY_FILTER_TYPE_TRAINING = "track_filter_type_training"
        private const val KEY_FILTER_TYPE_ROUTE = "track_filter_type_route"
        private const val KEY_STATS_MAP_SOURCE = "stats_map_source"
        private const val KEY_STATS_TRACK_PAINT = "stats_track_paint"
        private const val KEY_COMPETITION_HALO = "competition_halo"
        private const val KEY_COMPETITION_SECONDS = "competition_seconds"
        private const val KEY_USER_MAX_HR = "user_max_hr"
        private const val KEY_USER_AGE = "user_age"
        private const val KEY_USER_SEX = "user_sex"
        private const val KEY_TURN_VOICE = "turn_voice"
        private const val KEY_DESKTOP_PORT = "desktop_server_port"
        private const val KEY_USER_WEIGHT = "user_weight_kg"
        private const val KEY_REC_COUNTDOWN = "rec_countdown"
        private const val KEY_LAP_COUNTDOWN = "lap_countdown"
        private const val KEY_GHOST_ENABLED = "ghost_enabled"
        private const val KEY_GHOST_SOURCE = "ghost_source"
        private const val KEY_ACTIVE_SPORT = "active_sport_id"
        private const val KEY_HUD_ADOPTED = "hud_layout_adopted"
        private const val KEY_LAST_INSTALL_ID = "last_install_work_id"
        private const val KEY_AUTO_LAP_EVERY_M = "auto_lap_every_m"
        private const val KEY_SIM_TRACK = "sim_track_id"
        private const val KEY_SIM_SPEED = "sim_speed"
        private const val KEY_REC_AUTO_PAUSE_SEC = "rec_auto_pause_sec"
        private const val KEY_FOLLOW_TRACK = "active_follow_track_id"
        private const val KEY_TRACK_COLOR_MODE = "track_color_mode"
        private const val KEY_TRACK_COLOR = "track_color"
        private const val KEY_FOLLOW_COLOR = "follow_color"
        private const val KEY_FOLLOW_WIDTH = "follow_width"
        private const val KEY_FOLLOW_ARROWS = "follow_arrows"
        private const val KEY_FOLLOW_ARROW_COLOR = "follow_arrow_color"
        private const val KEY_FOLLOW_ARROW_SIZE = "follow_arrow_size"
        private const val KEY_FOLLOW_PROGRESS = "follow_progress"
        private const val KEY_TRACKING_STYLE = "tracking_point_style"
        private const val KEY_TRACKING_COLOR = "tracking_point_color"
        private const val KEY_TRACKING_SIZE = "tracking_point_size"
        private const val KEY_GHOST_MARKER_STYLE = "ghost_marker_style"
        private const val KEY_GHOST_COLOR = "ghost_color"
        private const val KEY_GHOST_SIZE = "ghost_size"
        private const val KEY_MAP_CACHE_MB = "map_cache_size_mb"
        private const val KEY_PREFETCH_ON_FOLLOW = "prefetch_on_follow"
        private const val KEY_OFFROUTE_THRESHOLD = "offroute_threshold"
        private const val KEY_OFFROUTE_SOUND = "offroute_sound"
        private const val KEY_OFFROUTE_VIBRATE = "offroute_vibrate"
        private const val KEY_OFFROUTE_SPOKEN = "offroute_spoken"
        private const val KEY_ANN_ENABLED = "ann_enabled"
        private const val KEY_ANN_MODE = "ann_mode"
        private const val KEY_ANN_LANG = "ann_lang"
        private const val KEY_ANN_BEEP_SOUND = "ann_beep_sound"
        private const val KEY_TURN_HEADS_UP = "turn_heads_up"
        private const val KEY_ANN_BY_DIST = "ann_by_dist"
        private const val KEY_ANN_DIST_KM = "ann_dist_km"
        private const val KEY_ANN_BY_TIME = "ann_by_time"
        private const val KEY_ANN_TIME_MIN = "ann_time_min"
        private const val KEY_ANN_F_DIST = "ann_f_dist"
        private const val KEY_ANN_F_PACE = "ann_f_pace"
        private const val KEY_ANN_F_SPLIT = "ann_f_split"
        private const val KEY_ANN_F_ELEV = "ann_f_elev"
        private const val KEY_ANN_F_HR = "ann_f_hr"
        const val DEFAULT_TRACK_COLOR = "#E63946"
        const val DEFAULT_FOLLOW_COLOR = "#3A86FF"

        /** The ghost's purple, hardcoded in the map layer before it was configurable. */
        const val DEFAULT_GHOST_COLOR = "#9B5DE5"

        fun get(context: Context): ViewerPreferences =
            ViewerPreferences(context.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE))
    }
}
