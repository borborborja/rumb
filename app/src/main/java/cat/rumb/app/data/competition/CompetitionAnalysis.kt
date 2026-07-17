package cat.rumb.app.data.competition

import cat.rumb.app.data.gpx.GpxPoint

/** Gap of an attempt vs the best track at a given distance. Positive = attempt slower. */
data class GapSample(val distM: Double, val gapSeconds: Double)

/** Pure-JVM analysis helpers for the competition detail screen. */
object CompetitionAnalysis {

    /**
     * Samples the time gap between [attempt] and [best] at [buckets] evenly spaced distances in
     * (0, min(totalBest, totalAttempt)]. Time at each distance comes from a time-at-distance curve
     * built from the timed points of each track (linear interpolation on the distance axis).
     * Positive gap means the attempt is slower than the best at that distance.
     *
     * Returns an empty list if either track has fewer than 2 timed points or the common
     * distance is under 1 m.
     */
    fun gapOverDistance(best: List<GpxPoint>, attempt: List<GpxPoint>, buckets: Int = 300): List<GapSample> {
        val bestCurve = TrackCurve.of(best) ?: return emptyList()
        val attemptCurve = TrackCurve.of(attempt) ?: return emptyList()
        val total = minOf(bestCurve.totalDist, attemptCurve.totalDist)
        if (total < 1.0 || buckets <= 0) return emptyList()
        val out = ArrayList<GapSample>(buckets)
        for (i in 1..buckets) {
            val d = total * i / buckets
            out.add(GapSample(d, attemptCurve.timeAt(d) - bestCurve.timeAt(d)))
        }
        return out
    }

    /**
     * Milliseconds spent per heart-rate zone, as fractions of [maxHr]:
     * z0 <60%, z1 60–70%, z2 70–80%, z3 80–90%, z4 ≥90%.
     * Each segment between consecutive timed points is attributed to the zone of the later
     * point's heart rate; segments with a null HR or non-positive dt are skipped.
     */
    fun hrZones(points: List<GpxPoint>, maxHr: Int): LongArray {
        val zones = LongArray(5)
        if (maxHr <= 0) return zones
        val timed = points.filter { it.time != null }
        for (i in 1 until timed.size) {
            val dt = timed[i].time!!.toEpochMilli() - timed[i - 1].time!!.toEpochMilli()
            if (dt <= 0) continue
            val hr = timed[i].heartRate ?: continue
            val fraction = hr / maxHr
            val zone = when {
                fraction < 0.6 -> 0
                fraction < 0.7 -> 1
                fraction < 0.8 -> 2
                fraction < 0.9 -> 3
                else -> 4
            }
            zones[zone] += dt
        }
        return zones
    }

}
