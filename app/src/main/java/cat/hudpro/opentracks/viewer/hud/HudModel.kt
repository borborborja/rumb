package cat.hudpro.opentracks.viewer.hud

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

/** A single displayable metric. Each knows its label, unit and how to format a [LiveMetrics]. */
enum class HudMetric(val label: String, val unit: String, val format: (LiveMetrics) -> String) {
    SPEED("Velocitat", "km/h", { fmt1(it.speedKmh) }),
    AVG_SPEED("Vel. mitjana", "km/h", { fmt1(it.avgMovingSpeedKmh) }),
    MAX_SPEED("Vel. màx", "km/h", { fmt1(it.maxSpeedKmh) }),
    DISTANCE("Distància", "km", { fmt2(it.distanceKm) }),
    DURATION("Temps", "", { fmtDuration(it.totalTime) }),
    MOVING_TIME("Temps mov.", "", { fmtDuration(it.movingTime) }),
    PACE("Ritme", "/km", { fmtPace(it.paceMinPerKm) }),
    HEADING("Rumb", "°", { fmt0(it.bearingDeg) }),
    ELEV_GAIN("Desnivell +", "m", { fmt0(it.elevationGainM) }),
    ALTITUDE("Altitud", "m", { fmt0(it.altitudeM) }),
    SLOPE("Pendent", "%", { fmt1(it.slopePercent) }),
    VAM("VAM", "m/h", { fmt0(it.vamMeterPerHour) }),
    REMAINING("Restant", "km", { fmt2(it.remainingDistanceKm) }),
    OFF_ROUTE("Desviació", "m", { fmt0(it.offRouteMeters) }),
    ;

    fun value(m: LiveMetrics): String = format(m)

    companion object {
        private fun fmt0(v: Double?) = v?.roundToInt()?.toString() ?: "—"
        private fun fmt1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        private fun fmt2(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
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
enum class HudCategory { METRIC, CHART, CONTROL }

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

    fun idOf(metric: HudMetric) = "metric:${metric.name}"

    val elements: List<HudElement> = buildList {
        HudMetric.entries.forEach { add(HudElement(idOf(it), it.label, HudCategory.METRIC, it)) }
        add(HudElement(CHART_SPEED, "Gràfic velocitat", HudCategory.CHART))
        add(HudElement(CHART_ELEVATION, "Perfil altitud", HudCategory.CHART))
        add(HudElement(CONTROL_RECENTER, "Centrar/seguir", HudCategory.CONTROL))
        add(HudElement(CONTROL_COMPASS, "Brúixola", HudCategory.CONTROL))
        add(HudElement(CONTROL_ZOOM, "Zoom", HudCategory.CONTROL))
    }

    private val byId = elements.associateBy { it.id }
    fun byId(id: String): HudElement? = byId[id]
}

/**
 * A placed HUD element. [x],[y] are fractions [0,1] of the safe area for the widget's top-left
 * corner, so the layout scales across screen sizes. [elementId] resolves via [HudCatalog].
 */
@Serializable
data class HudWidget(
    val elementId: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
) {
    val element: HudElement? get() = HudCatalog.byId(elementId)
}

/** The full HUD configuration: freely-positioned widgets + a global size scale. */
@Serializable
data class HudLayout(
    val widgets: List<HudWidget> = emptyList(),
    val scale: Float = 1.0f,
) {
    fun contains(elementId: String) = widgets.any { it.elementId == elementId }

    fun add(elementId: String, x: Float = 0.4f, y: Float = 0.45f): HudLayout =
        if (contains(elementId)) this else copy(widgets = widgets + HudWidget(elementId, x, y))

    fun remove(elementId: String): HudLayout =
        copy(widgets = widgets.filterNot { it.elementId == elementId })

    fun moveTo(index: Int, x: Float, y: Float): HudLayout {
        if (index !in widgets.indices) return this
        val w = widgets[index].copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
        return copy(widgets = widgets.toMutableList().also { it[index] = w })
    }

    companion object {
        private fun m(metric: HudMetric, x: Float, y: Float) = HudWidget(HudCatalog.idOf(metric), x, y)

        val CYCLING = HudLayout(
            widgets = listOf(
                m(HudMetric.SPEED, 0.03f, 0.80f),
                m(HudMetric.AVG_SPEED, 0.03f, 0.66f),
                m(HudMetric.DISTANCE, 0.64f, 0.80f),
                m(HudMetric.DURATION, 0.64f, 0.66f),
                m(HudMetric.ELEV_GAIN, 0.64f, 0.04f),
                HudWidget(HudCatalog.CHART_SPEED, 0.03f, 0.05f),
                HudWidget(HudCatalog.CONTROL_RECENTER, 0.88f, 0.44f),
            ),
        )
        val TRAIL = HudLayout(
            widgets = listOf(
                m(HudMetric.PACE, 0.03f, 0.80f),
                m(HudMetric.DISTANCE, 0.03f, 0.66f),
                m(HudMetric.MOVING_TIME, 0.64f, 0.80f),
                m(HudMetric.ELEV_GAIN, 0.64f, 0.66f),
                m(HudMetric.REMAINING, 0.64f, 0.04f),
                HudWidget(HudCatalog.CHART_ELEVATION, 0.03f, 0.05f),
                HudWidget(HudCatalog.CONTROL_RECENTER, 0.88f, 0.44f),
            ),
        )
        val SKI = HudLayout(
            widgets = listOf(
                m(HudMetric.SPEED, 0.03f, 0.80f),
                m(HudMetric.MAX_SPEED, 0.03f, 0.66f),
                m(HudMetric.ALTITUDE, 0.64f, 0.80f),
                m(HudMetric.SLOPE, 0.64f, 0.66f),
                HudWidget(HudCatalog.CONTROL_COMPASS, 0.88f, 0.06f),
                HudWidget(HudCatalog.CONTROL_ZOOM, 0.88f, 0.30f),
                HudWidget(HudCatalog.CONTROL_RECENTER, 0.88f, 0.60f),
            ),
        )
        val DEFAULT = CYCLING
        val PRESETS = mapOf("Ciclisme" to CYCLING, "Trail" to TRAIL, "Esquí" to SKI)
    }
}
