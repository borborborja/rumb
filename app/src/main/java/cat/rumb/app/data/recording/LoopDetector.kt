package cat.rumb.app.data.recording

import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.viewer.hud.MetricsCalculator
import kotlin.math.abs

/**
 * Finds the moment you have gone round a circuit, so laps can start without pressing the flag — and
 * without the loop having to start where the recording did (there's usually an approach leg from
 * home). Pure JVM, so it tests in isolation.
 *
 * It does NOT hunt for the topological point where the path closes on itself (fragile). It looks for
 * the first pair (P0, P1) where at P1 you are back on your own earlier path at P0, HEADING THE SAME
 * WAY, having travelled a plausible circuit in between. P0 becomes the finish line — a circuit's
 * start line is arbitrary, you just need one.
 *
 * The bearing test is what separates a loop from an out-and-back: retracing your steps you're at the
 * same place heading the OPPOSITE way (180°), so it never fires. The unavoidable cost of that same
 * test: a loop ridden the other way round is, for its first metres, identical to an out-and-back and
 * cannot be detected — for that, the manual flag.
 *
 * Detection needs a SECOND pass by construction: one pass is indistinguishable from a lollipop or a
 * coincidence. So it detects repeats, which is correct — riding a 5 km out-and-back twice is lapping.
 */
class LoopDetector {

    /** A subsampled path point: cheap enough to scan thousands of per fix, dense enough to place a line. */
    private class Station(
        val seq: Long,
        val point: GeoPoint,
        val distM: Double,
        val totalMs: Long,
        val bearing: Double, // NaN when the trailing chord was too degenerate to trust
    )

    /** Where a detected lap opens and closes. [start] is the finish line; [close] is one lap later. */
    data class Match(
        val startSeq: Long,
        val startPoint: GeoPoint,
        val startDistM: Double,
        val startTotalMs: Long,
        val closeSeq: Long,
        val closeDistM: Double,
        val closeTotalMs: Long,
    ) {
        val lapLengthM: Double get() = closeDistM - startDistM
    }

    private val stations = ArrayList<Station>()
    private var capped = false

    // The open candidate: an earlier station we currently seem to be shadowing, and how far along
    // the shadow has advanced. `candStart` is fixed when the candidate opens; matching must move
    // forward along the path, never rewind, so it stays locked to the pass being retraced.
    private var candStartIdx = -1
    private var candLastIdx = -1
    private var candCloseSeq = 0L
    private var candCloseDistM = 0.0
    private var candCloseTotalMs = 0L

    // How far the min-length cursor has advanced: candidates only live in stations[0..maxIdx], which
    // is [travelled - LOOP_MIN_M] worth of path. This makes the "min travelled" guard structural.
    private var maxIdx = -1

    /**
     * Feed every accepted fix, after the odometer is updated. Returns a [Match] the moment a loop is
     * confirmed — the caller then seeds laps and drops the detector. Null otherwise.
     */
    fun onFix(seq: Long, here: GeoPoint, distM: Double, totalMs: Long): Match? {
        maybeAddStation(seq, here, distM, totalMs)

        val myBearing = chordBearing(here, distM)
        if (myBearing.isNaN()) return null

        // Advance the candidate window: stations far enough behind that a match there is a real lap.
        while (maxIdx + 1 < stations.size && stations[maxIdx + 1].distM <= distM - LOOP_MIN_M) maxIdx++
        if (maxIdx < 0) return null

        val winner = bestMatch(here, myBearing)
        if (winner < 0) {
            candStartIdx = -1
            candLastIdx = -1
            return null
        }

        if (candStartIdx < 0) {
            candStartIdx = winner
            candCloseSeq = seq
            candCloseDistM = distM
            candCloseTotalMs = totalMs
        }
        candLastIdx = winner

        // Sustained for a whole confirm distance: a chance brush past your own path matches for one
        // or two stations, a real second lap keeps matching. The single best false-positive filter.
        if (distM - candCloseDistM >= CONFIRM_M) {
            val p0 = stations[candStartIdx]
            return Match(
                startSeq = p0.seq, startPoint = p0.point, startDistM = p0.distM, startTotalMs = p0.totalMs,
                closeSeq = candCloseSeq, closeDistM = candCloseDistM, closeTotalMs = candCloseTotalMs,
            )
        }
        return null
    }

    private fun maybeAddStation(seq: Long, here: GeoPoint, distM: Double, totalMs: Long) {
        if (capped) return
        val last = stations.lastOrNull()
        if (last != null && distM - last.distM < STATION_SPACING_M) return
        if (stations.size >= MAX_STATIONS) {
            capped = true
            return
        }
        stations.add(Station(seq, here, distM, totalMs, chordBearing(here, distM)))
    }

    /**
     * Bearing over a trailing 40 m chord, not a single 10 m leg (that would be ±30° of noise). The
     * SAME rule computes both sides of every comparison, so a chord's rotation on a curve cancels —
     * consistency matters more than absolute accuracy here. NaN when the chord spans too little (or
     * too much) ground to be a real heading: a hairpin, a pause/teleport gap, or GPS scatter.
     */
    private fun chordBearing(here: GeoPoint, distM: Double): Double {
        var j = stations.size - 1
        while (j >= 0 && distM - stations[j].distM < CHORD_M) j--
        if (j < 0) return Double.NaN
        val from = stations[j].point
        val ground = MetricsCalculator.distanceMeters(from, here)
        val travelled = distM - stations[j].distM
        if (ground < travelled * 0.5 || ground > travelled * 1.5) return Double.NaN
        return MetricsCalculator.bearing(from, here)
    }

    /**
     * The earlier station we're shadowing, or -1. Bounding-box pre-filter first (two float compares,
     * no trig) so the per-fix cost stays microseconds even at 15k stations. With a candidate open,
     * keep advancing along the path (index >= candLastIdx) and take the nearest such; otherwise the
     * geometrically nearest.
     */
    private fun bestMatch(here: GeoPoint, myBearing: Double): Int {
        val cosLat = Math.cos(Math.toRadians(here.latitude)).coerceAtLeast(1e-6)
        val dLatMax = MATCH_RADIUS_M / 111_320.0
        val dLonMax = MATCH_RADIUS_M / (111_320.0 * cosLat)

        val lo = if (candStartIdx >= 0) candLastIdx else 0
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in lo..maxIdx) {
            val s = stations[i]
            if (abs(s.point.latitude - here.latitude) > dLatMax) continue
            if (abs(s.point.longitude - here.longitude) > dLonMax) continue
            if (s.bearing.isNaN() || angDelta(s.bearing, myBearing) > BEARING_TOL_DEG) continue
            val d = MetricsCalculator.distanceMeters(s.point, here)
            if (d > MATCH_RADIUS_M) continue
            if (candStartIdx >= 0) return i // nearest-in-index along the path: first survivor wins
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private companion object {
        const val STATION_SPACING_M = 10.0
        const val MATCH_RADIUS_M = 20.0
        const val LOOP_MIN_M = 300.0
        const val BEARING_TOL_DEG = 50.0
        const val CHORD_M = 40.0
        const val CONFIRM_M = 60.0
        const val MAX_STATIONS = 50_000 // ~150 km at 10 m spacing; a safety valve, not a real limit

        /** Smallest angle between two bearings, in [0, 180]. */
        fun angDelta(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)
    }
}
