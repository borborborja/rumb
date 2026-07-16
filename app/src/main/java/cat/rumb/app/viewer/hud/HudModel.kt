package cat.rumb.app.viewer.hud

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

/** A single displayable metric. Formats a [LiveMetrics] value and its unit label under the chosen [Units]. */
enum class HudMetric(val labelRes: Int) {
    SPEED(cat.rumb.app.R.string.metric_speed),
    AVG_SPEED(cat.rumb.app.R.string.metric_avg_speed),
    MAX_SPEED(cat.rumb.app.R.string.metric_max_speed),
    DISTANCE(cat.rumb.app.R.string.metric_distance),
    DURATION(cat.rumb.app.R.string.metric_duration),
    MOVING_TIME(cat.rumb.app.R.string.metric_moving_time),
    PACE(cat.rumb.app.R.string.metric_pace),
    AVG_PACE(cat.rumb.app.R.string.metric_avg_pace),
    HEADING(cat.rumb.app.R.string.metric_heading),
    ELEV_GAIN(cat.rumb.app.R.string.metric_elev_gain),
    ALTITUDE(cat.rumb.app.R.string.metric_altitude),
    SLOPE(cat.rumb.app.R.string.metric_slope),
    VAM(cat.rumb.app.R.string.metric_vam),
    HEART_RATE(cat.rumb.app.R.string.metric_heart_rate),
    CADENCE(cat.rumb.app.R.string.metric_cadence),
    POWER(cat.rumb.app.R.string.metric_power),
    REMAINING(cat.rumb.app.R.string.metric_remaining),
    OFF_ROUTE(cat.rumb.app.R.string.metric_off_route),
    GHOST_DELTA(cat.rumb.app.R.string.metric_ghost_delta),
    GHOST_SECONDS(cat.rumb.app.R.string.metric_ghost_seconds),
    CALORIES(cat.rumb.app.R.string.metric_calories),
    LAP_COUNT(cat.rumb.app.R.string.metric_lap_count),
    LAP_TIME(cat.rumb.app.R.string.metric_lap_time),
    LAP_DISTANCE(cat.rumb.app.R.string.metric_lap_distance),
    LAST_LAP(cat.rumb.app.R.string.metric_last_lap),
    LAP_AVG_SPEED(cat.rumb.app.R.string.metric_lap_avg_speed),
    LAP_AVG_PACE(cat.rumb.app.R.string.metric_lap_avg_pace),
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
        // Average pace: what a runner actually watches. Derived from moving speed, like AVG_SPEED.
        AVG_PACE -> fmtPace(pacePerUnit(MetricsCalculator.paceFromSpeedKmh(m.avgMovingSpeedKmh), u.distance))
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
        GHOST_DELTA -> m.ghostDeltaMeters?.let { if (it >= 0) "+${it.toInt()}" else "${it.toInt()}" } ?: "—"
        GHOST_SECONDS -> m.ghostSecondsEst?.let { val s = it.roundToInt(); if (s >= 0) "+$s" else "$s" } ?: "—"
        CALORIES -> m.caloriesKcal?.toString() ?: "—"
        LAP_COUNT -> m.lapCount.takeIf { it > 0 }?.toString() ?: "—"
        LAP_TIME -> fmtDurationN(m.currentLapTime)
        LAP_DISTANCE -> fmt2(m.currentLapDistanceKm?.let { u.distance.fromKm(it) })
        LAST_LAP -> fmtDurationN(m.lastLapTime)
        LAP_AVG_SPEED -> fmt1(lapAvgSpeedKmh(m)?.let { u.speed.fromKmh(it) })
        LAP_AVG_PACE -> fmtPace(pacePerUnit(MetricsCalculator.paceFromSpeedKmh(lapAvgSpeedKmh(m)), u.distance))
    }

    /** Unit label under [u] (defaults to metric). Empty for unit-less metrics (durations). */
    fun unit(u: Units = Units()): String = when (this) {
        SPEED, AVG_SPEED, MAX_SPEED -> u.speed.label
        DISTANCE, REMAINING -> u.distance.label
        DURATION, MOVING_TIME -> ""
        PACE, AVG_PACE -> "/${u.distance.label}"
        HEADING -> "°"
        ELEV_GAIN, ALTITUDE, OFF_ROUTE -> u.elevation.label
        SLOPE -> "%"
        VAM -> "${u.elevation.label}/h"
        HEART_RATE -> "bpm"
        CADENCE -> u.cadence.label
        POWER -> "W"
        GHOST_DELTA -> "m"
        GHOST_SECONDS -> "s"
        CALORIES -> "kcal"
        LAP_COUNT -> ""
        LAP_TIME, LAST_LAP -> ""
        LAP_DISTANCE -> u.distance.label
        LAP_AVG_SPEED -> u.speed.label
        LAP_AVG_PACE -> "/${u.distance.label}"
    }

    companion object {
        private fun fmt0(v: Double?) = v?.roundToInt()?.toString() ?: "—"
        private fun fmt1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        private fun fmt2(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

        /** Minutes per selected distance unit, from a pace in min/km. */
        private fun pacePerUnit(paceMinPerKm: Double?, distance: DistanceUnit): Double? =
            paceMinPerKm?.let { it * distance.kmPerUnit }

        /**
         * Average speed of the lap in progress — since you crossed the start line, not since you
         * pressed record. Derived here rather than stored, like [AVG_PACE]: both inputs are already
         * on [LiveMetrics], and they are null outside a lap block, so the tile reads "—" during the
         * approach. The clock is ACTIVE time (it freezes on pause), which is what a lap is timed on.
         */
        private fun lapAvgSpeedKmh(m: LiveMetrics): Double? {
            val km = m.currentLapDistanceKm ?: return null
            val hours = (m.currentLapTime ?: return null).inWholeMilliseconds / 3_600_000.0
            return if (hours > 0) km / hours else null
        }

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
        private fun fmtDurationN(d: Duration?): String = d?.let { fmtDuration(it) } ?: "—"
    }
}

/** Category of a placeable HUD element (drives palette grouping and rendering). */
enum class HudCategory { METRIC, CHART, CONTROL, EXTRA }

/** Descriptor of a placeable HUD element, resolvable from a stable [id]. */
data class HudElement(
    val id: String,
    val labelRes: Int,
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
        HudMetric.entries.forEach { add(HudElement(idOf(it), it.labelRes, HudCategory.METRIC, it)) }
        add(HudElement(CHART_SPEED, cat.rumb.app.R.string.hudel_chart_speed, HudCategory.CHART))
        add(HudElement(CHART_ELEVATION, cat.rumb.app.R.string.hudel_chart_elevation, HudCategory.CHART))
        add(HudElement(CONTROL_RECENTER, cat.rumb.app.R.string.hudel_recenter, HudCategory.CONTROL))
        add(HudElement(CONTROL_COMPASS, cat.rumb.app.R.string.hudel_compass, HudCategory.CONTROL))
        add(HudElement(CONTROL_ZOOM, cat.rumb.app.R.string.hudel_zoom, HudCategory.CONTROL))
        add(HudElement(CONTROL_RECORD, cat.rumb.app.R.string.hudel_record, HudCategory.CONTROL))
        add(HudElement(WIDGET_CLOCK, cat.rumb.app.R.string.hudel_clock, HudCategory.EXTRA))
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

    /**
     * Moves the widget at [from] to [zone], inserting it at the flat-list position of the sibling
     * [targetElementId] — so dropping a widget onto another reorders it within a zone (or across
     * zones). A null/absent target appends it (last in [zone]). Order within a zone is the widgets'
     * relative order in the flat list, so re-inserting is what actually reorders.
     */
    fun moveWidget(from: Int, zone: HudZone, targetElementId: String?): HudLayout {
        if (from !in widgets.indices) return this
        val to = targetElementId?.let { id -> widgets.indexOfFirst { it.elementId == id } }?.takeIf { it >= 0 }
        val list = widgets.toMutableList()
        val w = list.removeAt(from).copy(zone = zone)
        list.add((to ?: list.size).coerceIn(0, list.size), w)
        return copy(widgets = list)
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
        /** Road running: pace first (a runner never thinks in km/h), average pace next to it. */
        val RUNNING = HudLayout(
            widgets = listOf(
                m(HudMetric.PACE, HudZone.BOTTOM_LEFT),
                m(HudMetric.AVG_PACE, HudZone.BOTTOM_LEFT),
                m(HudMetric.DISTANCE, HudZone.BOTTOM_RIGHT),
                m(HudMetric.MOVING_TIME, HudZone.BOTTOM_RIGHT),
                m(HudMetric.HEART_RATE, HudZone.TOP_RIGHT),
                m(HudMetric.LAP_TIME, HudZone.TOP_RIGHT),
                HudWidget(HudCatalog.CONTROL_RECENTER, HudZone.MIDDLE_RIGHT),
                HudWidget(HudCatalog.CONTROL_RECORD, HudZone.MIDDLE_RIGHT),
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
        val PRESETS = mapOf(
            cat.rumb.app.R.string.preset_cycling to CYCLING,
            cat.rumb.app.R.string.preset_running to RUNNING,
            cat.rumb.app.R.string.preset_trail to TRAIL,
            cat.rumb.app.R.string.preset_ski to SKI,
        )
        const val MIN_WIDGET_SCALE = 0.6f
        const val MAX_WIDGET_SCALE = 2.2f
    }
}
