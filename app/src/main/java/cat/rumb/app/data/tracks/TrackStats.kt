package cat.rumb.app.data.tracks

import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.viewer.hud.MetricsCalculator
import java.time.Duration
import java.time.Instant

/** Aggregate statistics of a saved track, computed from its points. */
data class TrackStats(
    val distanceM: Double,
    val totalTime: Duration?,
    val movingTime: Duration?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val ascentM: Double,
    val descentM: Double,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
)

/** One decimated sample along the track, for charts + the map scrubber. */
data class TrackSample(
    val distM: Double,
    val lat: Double,
    val lon: Double,
    val elevation: Float?,
    val speedKmh: Float?,
    val hr: Float?,
    val time: Instant?,
)

/**
 * The sample nearest to [distM] along [samples], or null when there is nothing to point at.
 * Shared by every screen that turns a chart scrub into a place on the map.
 */
fun nearestSampleAt(samples: List<TrackSample>, distM: Double): TrackSample? =
    samples.minByOrNull { kotlin.math.abs(it.distM - distM) }

/** The sample at [fraction] (0..1) of the track's total distance. */
fun nearestSampleAtFraction(samples: List<TrackSample>, fraction: Float): TrackSample? {
    if (samples.isEmpty()) return null
    return nearestSampleAt(samples, fraction * samples.last().distM)
}

object TrackStatsCalculator {

    private const val MOVING_SPEED_KMH = 1.0
    private const val SMOOTH_WINDOW = 5

    /** Computes aggregate stats. Speeds come from consecutive point deltas, smoothed to kill jitter. */
    fun compute(points: List<GpxPoint>): TrackStats {
        val distances = cumulativeDistances(points)
        val distanceM = distances.lastOrNull() ?: 0.0
        val raw = rawSpeeds(points, distances)
        val speeds = smooth(raw)

        val first = points.firstOrNull()?.time
        val last = points.lastOrNull()?.time
        val totalTime = if (first != null && last != null && last > first) Duration.between(first, last) else null

        var movingMs = 0L
        for (i in 1 until points.size) {
            val t0 = points[i - 1].time ?: continue
            val t1 = points[i].time ?: continue
            // Raw (per-interval) speed decides moving vs stopped; smoothing would wash out short stops.
            val speed = raw.getOrNull(i)
            if (speed != null && speed > MOVING_SPEED_KMH) movingMs += Duration.between(t0, t1).toMillis()
        }
        val movingTime = if (movingMs > 0) Duration.ofMillis(movingMs) else null

        val avgSpeedKmh = movingTime?.let { if (it.seconds > 0) distanceM / 1000.0 / (it.seconds / 3600.0) else null }
            ?: totalTime?.let { if (it.seconds > 0) distanceM / 1000.0 / (it.seconds / 3600.0) else null }
        val maxSpeedKmh = speeds.filterNotNull().maxOrNull()

        var descent = 0.0
        var prevEle: Double? = null
        for (p in points) {
            val e = p.elevation ?: continue
            prevEle?.let { if (e < it) descent += it - e }
            prevEle = e
        }

        val hrs = points.mapNotNull { it.heartRate }
        val cads = points.mapNotNull { it.cadence }
        val pows = points.mapNotNull { it.power }

        return TrackStats(
            distanceM = distanceM,
            totalTime = totalTime,
            movingTime = movingTime,
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            ascentM = TrackRepository.ascent(points),
            descentM = descent,
            avgHr = hrs.takeIf { it.isNotEmpty() }?.average(),
            maxHr = hrs.maxOrNull(),
            avgCadence = cads.takeIf { it.isNotEmpty() }?.average(),
            avgPower = pows.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /**
     * Decimates the track into at most [maxSamples] samples bucketed by cumulative distance,
     * averaging elevation/speed/HR per bucket. Keeps chart rendering cheap for 10k-point tracks.
     */
    fun samples(points: List<GpxPoint>, maxSamples: Int = 600): List<TrackSample> {
        if (points.isEmpty()) return emptyList()
        val distances = cumulativeDistances(points)
        val total = distances.last()
        val speeds = smooth(rawSpeeds(points, distances))
        if (points.size <= maxSamples || total <= 0.0) {
            return points.indices.map { i ->
                TrackSample(
                    distM = distances[i],
                    lat = points[i].latitude,
                    lon = points[i].longitude,
                    elevation = points[i].elevation?.toFloat(),
                    speedKmh = speeds[i]?.toFloat(),
                    hr = points[i].heartRate?.toFloat(),
                    time = points[i].time,
                )
            }
        }

        val bucketSize = total / maxSamples
        val out = ArrayList<TrackSample>(maxSamples)
        var start = 0
        for (b in 0 until maxSamples) {
            // Clamp the last bucket to the exact total so FP rounding can't drop the final point(s).
            val end = if (b == maxSamples - 1) total else (b + 1) * bucketSize
            var i = start
            while (i < points.size && distances[i] <= end) i++
            if (i == start) continue // empty bucket
            val slice = start until i
            val mid = slice.first + slice.count() / 2
            out.add(
                TrackSample(
                    distM = distances[mid],
                    lat = points[mid].latitude,
                    lon = points[mid].longitude,
                    elevation = slice.mapNotNull { points[it].elevation }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    speedKmh = slice.mapNotNull { speeds[it] }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    hr = slice.mapNotNull { points[it].heartRate }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    time = points[mid].time,
                ),
            )
            start = i
        }
        return out
    }

    private fun cumulativeDistances(points: List<GpxPoint>): List<Double> {
        val out = ArrayList<Double>(points.size)
        var acc = 0.0
        for (i in points.indices) {
            if (i > 0) acc += MetricsCalculator.distanceMeters(points[i - 1].toGeoPoint(), points[i].toGeoPoint())
            out.add(acc)
        }
        return out
    }

    /** Per-point speed (km/h) from consecutive deltas. Null without time. */
    private fun rawSpeeds(points: List<GpxPoint>, distances: List<Double>): List<Double?> {
        val raw = ArrayList<Double?>(points.size)
        for (i in points.indices) {
            if (i == 0) { raw.add(null); continue }
            val t0 = points[i - 1].time
            val t1 = points[i].time
            val dt = if (t0 != null && t1 != null) Duration.between(t0, t1).toMillis() / 1000.0 else 0.0
            raw.add(if (dt > 0) (distances[i] - distances[i - 1]) / dt * 3.6 else null)
        }
        return raw
    }

    /** Centered moving average to kill GPS jitter (for max speed and charts). */
    private fun smooth(raw: List<Double?>): List<Double?> {
        val half = SMOOTH_WINDOW / 2
        return raw.indices.map { i ->
            val window = (maxOf(0, i - half)..minOf(raw.lastIndex, i + half)).mapNotNull { raw[it] }
            if (window.isEmpty()) null else window.average()
        }
    }
}
