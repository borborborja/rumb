package cat.hudpro.opentracks.viewer.hud

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

/** A single displayable metric. Formats a [LiveMetrics] value and its unit label under the chosen [Units]. */
enum class HudMetric(val label: String) {
    SPEED("Velocitat"),
    AVG_SPEED("Vel. mitjana"),
    MAX_SPEED("Vel. màx"),
    DISTANCE("Distància"),
    DURATION("Temps"),
    MOVING_TIME("Temps mov."),
    PACE("Ritme"),
    HEADING("Rumb"),
    ELEV_GAIN("Desnivell +"),
    ALTITUDE("Altitud"),
    SLOPE("Pendent"),
    VAM("VAM"),
    HEART_RATE("FC"),
    CADENCE("Cadència"),
    POWER("Potència"),
    REMAINING("Restant"),
    OFF_ROUTE("Desviació"),
    ;

    /** Formatted value string for [m] under [u] (defaults to metric). */
    fun value(m: LiveMetrics, u: Units = Units()): String = when (this) {
        SPEED -> fmt1(m.speedKmh?.let { u.speed.fromKmh(it) })
        AVG_SPEED -> fmt1(m.avgMovingSpeedKmh?.let { u.speed.fromKmh(it) })
        MAX_SPEED -> fmt1(m.maxSpeedKmh?.let { u.speed.fromKmh(it) })
        DISTANCE -> fmt2(u.distance.fromKm(m.distanceKm))
        DURATION -> fmtDuration(m.totalTime)
        MOVING_TIME -> fmtDuration(m.movingTime)
        PACE -> fmtPace(pacePerUnit(m.paceMinPerKm, u.distance))
        HEADING -> fmt0(m.bearingDeg)
        ELEV_GAIN -> fmt0(m.elevationGainM?.let { u.elevation.fromMeters(it) })
        ALTITUDE -> fmt0(m.altitudeM?.let { u.elevation.fromMeters(it) })
        SLOPE -> fmt1(m.slopePercent)
        VAM -> fmt0(m.vamMeterPerHour?.let { u.elevation.fromMeters(it) })
        HEART_RATE -> fmt0(m.heartRateBpm)
        CADENCE -> fmt0(m.cadenceRpm)
        POWER -> fmt0(m.powerW)
        REMAINING -> fmt2(m.remainingDistanceKm?.let { u.distance.fromKm(it) })
        OFF_ROUTE -> fmt0(m.offRouteMeters?.let { u.elevation.fromMeters(it) })
    }

    /** Unit label under [u] (defaults to metric). Empty for unit-less metrics (durations). */
    fun unit(u: Units = Units()): String = when (this) {
        SPEED, AVG_SPEED, MAX_SPEED -> u.speed.label
        DISTANCE, REMAINING -> u.distance.label
        DURATION, MOVING_TIME -> ""
        PACE -> "/${u.distance.label}"
        HEADING -> "°"
        ELEV_GAIN, ALTITUDE, OFF_ROUTE -> u.elevation.label
        SLOPE -> "%"
        VAM -> "${u.elevation.label}/h"
        HEART_RATE -> "bpm"
        CADENCE -> "rpm"
        POWER -> "W"
    }

    companion object {
        private fun fmt0(v: Double?) = v?.roundToInt()?.toString() ?: "—"
        private fun fmt1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        private fun fmt2(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

        /** Minutes per selected distance unit, from a pace in min/km. */
        private fun pacePerUnit(paceMinPerKm: Double?, distance: DistanceUnit): Double? =
            paceMinPerKm?.let { it * distance.kmPerUnit }

        private fun fmtPace(v: Double?): String {
            if (v == null) return "—"
            val totalSec = (v * 60).roundToInt()
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
        private fun fmtDuration(d: Duration): String {
            val s = d.inWholeSeconds
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}

/** Category of a placeable HUD element (drives palette grouping and rendering). */
enum class HudCategory { METRIC, CHART, CONTROL, EXTRA }

/** Descriptor of a placeable HUD element, resolvable from a stable [id]. */
data class HudElement(
    val id: String,
    val label: String,
    val category: HudCategory,
    val metric: HudMetric? = null,
)

/** Registry of every element that can be placed on the HUD. Ids are stable & serialized. */
object HudCatalog {
    const val CHART_SPEED = "chart:speed"
    const val CHART_ELEVATION = "chart:elevation"
    const val CONTROL_RECENTER = "control:recenter"
    const val CONTROL_COMPASS = "control:compass"
    const val CONTROL_ZOOM = "control:zoom"
    const val CONTROL_RECORD = "control:record"
    const val WIDGET_CLOCK = "widget:clock"

    fun idOf(metric: HudMetric) = "metric:${metric.name}"

    val elements: List<HudElement> = buildList {
        HudMetric.entries.forEach { add(HudElement(idOf(it), it.label, HudCategory.METRIC, it)) }
        add(HudElement(CHART_SPEED, "Gràfic velocitat", HudCategory.CHART))
        add(HudElement(CHART_ELEVATION, "Perfil altitud", HudCategory.CHART))
        add(HudElement(CONTROL_RECENTER, "Centrar/seguir", HudCategory.CONTROL))
        add(HudElement(CONTROL_COMPASS, "Brúixola", HudCategory.CONTROL))
        add(HudElement(CONTROL_ZOOM, "Zoom", HudCategory.CONTROL))
        add(HudElement(CONTROL_RECORD, "Gravació", HudCategory.CONTROL))
        add(HudElement(WIDGET_CLOCK, "Rellotge", HudCategory.EXTRA))
    }

    private val byId = elements.associateBy { it.id }
    fun byId(id: String): HudElement? = byId[id]
}

/**
 * Screen zone where a widget is placed. Widgets in the same zone auto-stack (no overlap). No
 * center-center zone so the map middle stays clear.
 */
@Serializable
enum class HudZone(val label: String) {
    TOP_LEFT("↖"), TOP_CENTER("↑"), TOP_RIGHT("↗"),
    MIDDLE_LEFT("←"), MIDDLE_RIGHT("→"),
    BOTTOM_LEFT("↙"), BOTTOM_CENTER("↓"), BOTTOM_RIGHT("↘"),
    ;

    val isCenter: Boolean get() = this == TOP_CENTER || this == BOTTOM_CENTER
    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT
}

/** A placed HUD element assigned to a [zone]. Stacking order follows the list order. */
@Serializable
data class HudWidget(
    val elementId: String,
    val zone: HudZone,
    val scale: Float = 1f,
    /** Per-widget options (see HudOption keys): color, chart, 12/24h clock… */
    val options: Map<String, String> = emptyMap(),
) {
    val element: HudElement? get() = HudCatalog.byId(elementId)
}

/** Recognized per-widget option keys. */
object HudOption {
    /** Hex color ("#RRGGBB") of the value text; absent = white. */
    const val COLOR = "color"
    /** "1" → show a mini history chart under the value (speed/FC/cadència/potència). */
    const val CHART = "chart"
    /** Clock format: "1" (default) = 24 h, "0" = 12 h. */
    const val H24 = "h24"
}

/** The full HUD configuration: zone-placed widgets + a global size scale. */
@Serializable
data class HudLayout(
    val widgets: List<HudWidget> = emptyList(),
    val scale: Float = 1.0f,
) {
    fun contains(elementId: String) = widgets.any { it.elementId == elementId }

    fun byZone(zone: HudZone): List<HudWidget> = widgets.filter { it.zone == zone }

    fun add(elementId: String, zone: HudZone = HudZone.TOP_LEFT): HudLayout =
        if (contains(elementId)) this else copy(widgets = widgets + HudWidget(elementId, zone))

    fun remove(elementId: String): HudLayout =
        copy(widgets = widgets.filterNot { it.elementId == elementId })

    fun moveToZone(index: Int, zone: HudZone): HudLayout {
        if (index !in widgets.indices) return this
        val w = widgets[index].copy(zone = zone)
        return copy(widgets = widgets.toMutableList().also { it[index] = w })
    }

    /** Sets one widget's individual scale, clamped to a sane range. */
    fun setWidgetScale(index: Int, scale: Float): HudLayout {
        if (index !in widgets.indices) return this
        val w = widgets[index].copy(scale = scale.coerceIn(MIN_WIDGET_SCALE, MAX_WIDGET_SCALE))
        return copy(widgets = widgets.toMutableList().also { it[index] = w })
    }

    /** Sets (or clears with null) one widget option. */
    fun setWidgetOption(index: Int, key: String, value: String?): HudLayout {
        if (index !in widgets.indices) return this
        val current = widgets[index]
        val options = if (value == null) current.options - key else current.options + (key to value)
        return copy(widgets = widgets.toMutableList().also { it[index] = current.copy(options = options) })
    }

    companion object {
        private fun m(metric: HudMetric, zone: HudZone) = HudWidget(HudCatalog.idOf(metric), zone)

        val CYCLING = HudLayout(
            widgets = listOf(
                m(HudMetric.SPEED, HudZone.BOTTOM_LEFT),
                m(HudMetric.AVG_SPEED, HudZone.BOTTOM_LEFT),
                m(HudMetric.DISTANCE, HudZone.BOTTOM_RIGHT),
                m(HudMetric.DURATION, HudZone.BOTTOM_RIGHT),
                m(HudMetric.ELEV_GAIN, HudZone.TOP_RIGHT),
                HudWidget(HudCatalog.CHART_SPEED, HudZone.TOP_LEFT),
                HudWidget(HudCatalog.CONTROL_RECENTER, HudZone.MIDDLE_RIGHT),
                HudWidget(HudCatalog.CONTROL_RECORD, HudZone.MIDDLE_RIGHT),
            ),
        )
        val TRAIL = HudLayout(
            widgets = listOf(
                m(HudMetric.PACE, HudZone.BOTTOM_LEFT),
                m(HudMetric.DISTANCE, HudZone.BOTTOM_LEFT),
                m(HudMetric.MOVING_TIME, HudZone.BOTTOM_RIGHT),
                m(HudMetric.ELEV_GAIN, HudZone.BOTTOM_RIGHT),
                m(HudMetric.REMAINING, HudZone.TOP_RIGHT),
                HudWidget(HudCatalog.CHART_ELEVATION, HudZone.TOP_LEFT),
                HudWidget(HudCatalog.CONTROL_RECENTER, HudZone.MIDDLE_RIGHT),
            ),
        )
        val SKI = HudLayout(
            widgets = listOf(
                m(HudMetric.SPEED, HudZone.BOTTOM_LEFT),
                m(HudMetric.MAX_SPEED, HudZone.BOTTOM_LEFT),
                m(HudMetric.ALTITUDE, HudZone.BOTTOM_RIGHT),
                m(HudMetric.SLOPE, HudZone.BOTTOM_RIGHT),
                HudWidget(HudCatalog.CONTROL_COMPASS, HudZone.MIDDLE_RIGHT),
                HudWidget(HudCatalog.CONTROL_ZOOM, HudZone.MIDDLE_RIGHT),
                HudWidget(HudCatalog.CONTROL_RECENTER, HudZone.MIDDLE_RIGHT),
            ),
        )
        val DEFAULT = CYCLING
        val PRESETS = mapOf("Ciclisme" to CYCLING, "Trail" to TRAIL, "Esquí" to SKI)
        const val MIN_WIDGET_SCALE = 0.6f
        const val MAX_WIDGET_SCALE = 2.2f
    }
}
