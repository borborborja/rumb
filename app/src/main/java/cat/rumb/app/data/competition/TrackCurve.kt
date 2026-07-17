package cat.rumb.app.data.competition

import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.viewer.hud.MetricsCalculator

/**
 * A track as two monotonic axes — cumulative distance and relative seconds — and the mapping between
 * them, in both directions. Pure JVM.
 *
 * [distanceAt] is what lets several attempts share one scrub: ask the leader what time it was when it
 * reached a point, then ask every rival where IT was at that same moment. That is the gap you can
 * actually see on a map; comparing rivals at the same DISTANCE only ever stacks them on top of
 * each other.
 */
class TrackCurve private constructor(
    private val dist: DoubleArray,
    private val time: DoubleArray,
) {
    val totalDist: Double get() = dist.last()
    val totalTime: Double get() = time.last()

    /**
     * Relative seconds at distance [distM], linearly interpolated on the distance axis.
     * Where the track pauses the distance repeats; the binary search returns the lower bound
     * (first index with dist >= d), i.e. the FIRST time at that distance.
     */
    fun timeAt(distM: Double): Double {
        if (distM <= dist.first()) return time.first()
        if (distM >= dist.last()) return time.last()
        val lo = lowerBound(dist, distM)
        val d0 = dist[lo - 1]
        val d1 = dist[lo]
        if (d1 <= d0) return time[lo]
        return time[lo - 1] + (time[lo] - time[lo - 1]) * ((distM - d0) / (d1 - d0))
    }

    /**
     * Distance covered at [seconds], linearly interpolated on the time axis — the inverse of
     * [timeAt]. Standing still (a pause) holds the distance: time advances while the lower bound
     * stays inside the flat stretch, which is exactly what a stopped rival should look like.
     * Clamped at both ends: past the finish it returns the total, so a shorter attempt simply
     * stops where it ended instead of running off the track.
     */
    fun distanceAt(seconds: Double): Double {
        if (seconds <= time.first()) return dist.first()
        if (seconds >= time.last()) return dist.last()
        val lo = lowerBound(time, seconds)
        val t0 = time[lo - 1]
        val t1 = time[lo]
        if (t1 <= t0) return dist[lo]
        return dist[lo - 1] + (dist[lo] - dist[lo - 1]) * ((seconds - t0) / (t1 - t0))
    }

    /** First index i with [axis]`[i] >= value`. Both axes are non-decreasing by construction. */
    private fun lowerBound(axis: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = axis.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (axis[mid] >= value) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        /** Builds the curve from the timed points, or null with fewer than 2 of them. */
        fun of(points: List<GpxPoint>): TrackCurve? {
            val timed = points.filter { it.time != null }
            if (timed.size < 2) return null
            val dist = DoubleArray(timed.size)
            val time = DoubleArray(timed.size)
            val t0 = timed.first().time!!.toEpochMilli()
            for (i in 1 until timed.size) {
                dist[i] = dist[i - 1] + MetricsCalculator.distanceMeters(timed[i - 1].toGeoPoint(), timed[i].toGeoPoint())
                val t = (timed[i].time!!.toEpochMilli() - t0) / 1000.0
                time[i] = maxOf(t, time[i - 1]) // coerce monotonic (guards GPS clock skew)
            }
            return TrackCurve(dist, time)
        }
    }
}
