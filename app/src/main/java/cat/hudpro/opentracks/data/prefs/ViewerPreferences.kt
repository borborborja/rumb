package cat.hudpro.opentracks.data.prefs

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

    /** Name of the selected [cat.hudpro.opentracks.data.map.TrackColorMode]. */
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

    var followArrows: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_ARROWS, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_ARROWS, value).apply()

    var followProgress: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_PROGRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_PROGRESS, value).apply()

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
        private const val KEY_DATA_LAYOUT = "data_layout_json"
        private const val KEY_UNIT_DISTANCE = "unit_distance"
        private const val KEY_UNIT_ELEVATION = "unit_elevation"
        private const val KEY_UNIT_SPEED = "unit_speed"
        private const val KEY_MAP_ORIENTATION = "map_orientation"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_FOLLOW_TRACK = "active_follow_track_id"
        private const val KEY_TRACK_COLOR_MODE = "track_color_mode"
        private const val KEY_TRACK_COLOR = "track_color"
        private const val KEY_FOLLOW_COLOR = "follow_color"
        private const val KEY_FOLLOW_WIDTH = "follow_width"
        private const val KEY_FOLLOW_ARROWS = "follow_arrows"
        private const val KEY_FOLLOW_PROGRESS = "follow_progress"
        private const val KEY_OFFROUTE_THRESHOLD = "offroute_threshold"
        private const val KEY_OFFROUTE_SOUND = "offroute_sound"
        private const val KEY_OFFROUTE_VIBRATE = "offroute_vibrate"
        private const val KEY_OFFROUTE_SPOKEN = "offroute_spoken"
        private const val KEY_ANN_ENABLED = "ann_enabled"
        private const val KEY_ANN_MODE = "ann_mode"
        private const val KEY_ANN_LANG = "ann_lang"
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

        fun get(context: Context): ViewerPreferences =
            ViewerPreferences(context.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE))
    }
}
