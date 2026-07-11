package cat.hudpro.opentracks.data.map

import cat.hudpro.opentracks.data.opentracks.model.Trackpoint

/** How the recorded track polyline is colored in the viewer. */
enum class TrackColorMode(val label: String) {
    SINGLE("Color únic"),
    SPEED("Per velocitat"),
    ALTITUDE("Per altitud"),
    HEART_RATE("Per pulsacions"),
    ;

    /** The numeric value to color by for a trackpoint, or null if unavailable in this mode. */
    fun valueOf(tp: Trackpoint): Double? = when (this) {
        SINGLE -> null
        SPEED -> tp.speed.takeIf { it >= 0.0 }?.times(3.6) // km/h
        ALTITUDE -> tp.altitude
        HEART_RATE -> tp.heartRate
    }

    companion object {
        fun byName(name: String?): TrackColorMode = entries.firstOrNull { it.name == name } ?: SPEED
    }
}
