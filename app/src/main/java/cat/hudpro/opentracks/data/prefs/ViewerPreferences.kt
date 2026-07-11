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

    /** Id of the track the user has chosen to follow, or null. */
    var activeFollowTrackId: Long
        get() = prefs.getLong(KEY_FOLLOW_TRACK, -1L)
        set(value) = prefs.edit().putLong(KEY_FOLLOW_TRACK, value).apply()

    /** Name of the selected [cat.hudpro.opentracks.data.map.TrackColorMode]. */
    var trackColorMode: String?
        get() = prefs.getString(KEY_TRACK_COLOR_MODE, null)
        set(value) = prefs.edit().putString(KEY_TRACK_COLOR_MODE, value).apply()

    /** Color (hex "#RRGGBB") used in SINGLE mode. */
    var trackColor: String
        get() = prefs.getString(KEY_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        set(value) = prefs.edit().putString(KEY_TRACK_COLOR, value).apply()

    companion object {
        private const val KEY_BASE_MAP = "base_map_id"
        private const val KEY_HUD_LAYOUT = "hud_layout_json"
        private const val KEY_FOLLOW_TRACK = "active_follow_track_id"
        private const val KEY_TRACK_COLOR_MODE = "track_color_mode"
        private const val KEY_TRACK_COLOR = "track_color"
        const val DEFAULT_TRACK_COLOR = "#E63946"

        fun get(context: Context): ViewerPreferences =
            ViewerPreferences(context.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE))
    }
}
