package cat.hudpro.opentracks.viewer.follow

import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.viewer.hud.MetricsCalculator

data class FollowState(
    val offRouteMeters: Double,
    val remainingKm: Double,
    val bearingToRouteDeg: Double?,
    val nearestIndex: Int,
) {
    fun isOffRoute(thresholdMeters: Double = 40.0) = offRouteMeters > thresholdMeters
}

/**
 * Breadcrumb navigation against a preloaded route. Vertex-based: finds the nearest route vertex to
 * the current position, then derives remaining distance (cumulative-to-end), lateral deviation and
 * the bearing to the next vertex ahead. Suitable for dense GPX; pure and unit-testable.
 */
class FollowRouteEngine(route: List<GeoPoint>, elevations: List<Double?> = emptyList()) {

    val points: List<GeoPoint> = route
    private val cumulative: DoubleArray = DoubleArray(route.size)
    val totalMeters: Double

    /** Elevation samples (m) aligned to [points], for the HUD elevation profile. Empty if unknown. */
    val elevationProfile: List<Float> =
        if (elevations.size == route.size && elevations.any { it != null }) {
            elevations.map { (it ?: 0.0).toFloat() }
        } else {
            emptyList()
        }

    init {
        var acc = 0.0
        for (i in 1 until route.size) {
            acc += MetricsCalculator.distanceMeters(route[i - 1], route[i])
            cumulative[i] = acc
        }
        totalMeters = acc
    }

    fun update(current: GeoPoint): FollowState? {
        if (points.isEmpty()) return null
        var nearest = 0
        var nearestDist = Double.MAX_VALUE
        for (i in points.indices) {
            val d = MetricsCalculator.distanceMeters(current, points[i])
            if (d < nearestDist) {
                nearestDist = d
                nearest = i
            }
        }
        val remaining = (totalMeters - cumulative[nearest]).coerceAtLeast(0.0)
        val target = points.getOrNull(nearest + 1) ?: points[nearest]
        val bearing = if (target != current) MetricsCalculator.bearing(current, target) else null
        return FollowState(
            offRouteMeters = nearestDist,
            remainingKm = remaining / 1000.0,
            bearingToRouteDeg = bearing,
            nearestIndex = nearest,
        )
    }
}
