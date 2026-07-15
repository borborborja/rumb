package cat.rumb.app.data.tracks

import cat.rumb.app.data.competition.GhostEngine
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Circuits (fixed start/finish line + lap-effort leaderboard) and their efforts. */
class CircuitRepository(
    private val dao: CircuitDao,
    private val trackRepository: TrackRepository,
) {
    fun observeCircuits(): Flow<List<CircuitEntity>> = dao.observeCircuits()
    fun effortsFor(circuitId: Long): Flow<List<CircuitEffortEntity>> = dao.effortsFor(circuitId)
    suspend fun getCircuit(id: Long): CircuitEntity? = dao.getCircuit(id)
    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun setArchived(id: Long, flag: Boolean) = dao.setArchived(id, flag)
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteEfforts(id); dao.deleteCircuit(id) }

    /** A lap slice with its computed time, ready to become an effort or the reference. */
    private data class LapSlice(val lapIndex: Int, val points: List<GpxPoint>, val stats: TrackStats, val timeMs: Long)

    private fun sliceLaps(pts: List<GpxPoint>, laps: List<LapRange>): List<LapSlice> =
        laps.filter { it.kind == LapKind.LAP }.mapIndexedNotNull { i, lap ->
            val from = lap.startIdx.coerceIn(0, pts.size)
            val to = lap.endIdx.coerceIn(from, pts.size)
            if (to - from < 2) return@mapIndexedNotNull null
            val slice = pts.subList(from, to)
            val stats = TrackStatsCalculator.compute(slice)
            val secs = (stats.movingTime ?: stats.totalTime)?.seconds ?: 0L
            LapSlice(i, slice, stats, secs * 1000)
        }

    /** Timed lap slices of [trackId], usable as circuit efforts (≥2 timed points, positive time). */
    private suspend fun timedSlices(trackId: Long): List<LapSlice> {
        val pts = trackRepository.loadGpxRoute(trackId)
        val laps = Laps.decode(trackRepository.get(trackId)?.laps)
        return sliceLaps(pts, laps).filter { it.timeMs > 0 && GhostEngine.isTimed(it.points) }
    }

    /** Inserts each slice as an effort of [circuitId] (dedup by unique index), then refreshes the best. */
    private suspend fun insertEffortsAndRefresh(circuitId: Long, trackId: Long, name: String, slices: List<LapSlice>, now: Long) {
        slices.forEach { s ->
            dao.insertEffort(
                CircuitEffortEntity(
                    circuitId = circuitId,
                    sourceTrackId = trackId,
                    lapIndex = s.lapIndex,
                    timeMs = s.timeMs,
                    distanceM = s.stats.distanceM,
                    avgHr = s.stats.avgHr,
                    createdAt = now,
                    gpx = Gpx.write(name, s.points),
                ),
            )
        }
        // The fastest effort overall is the ghost/parent; the frozen line columns never change.
        val best = dao.effortsForOnce(circuitId).minByOrNull { it.timeMs }
        if (best != null) dao.updateReference(circuitId, best.gpx, best.id)
    }

    /**
     * Seeds a circuit from [trackId]'s laps: the fastest LAP becomes the reference (line + ghost),
     * and every LAP is inserted as an initial effort. Returns the new circuit id, or null when the
     * source has no usable (timed, ≥2-point) laps.
     */
    suspend fun createCircuitFromTrack(trackId: Long, name: String, activityType: String?, now: Long): Long? =
        withContext(Dispatchers.IO) {
            val slices = timedSlices(trackId)
            if (slices.isEmpty()) return@withContext null
            val parent = slices.minByOrNull { it.timeMs }!!
            val line = parent.points.first()
            val circuitId = dao.insertCircuit(
                CircuitEntity(
                    name = name,
                    activityType = activityType,
                    createdAt = now,
                    lineLat = line.latitude,
                    lineLng = line.longitude,
                    referenceGpx = Gpx.write(name, parent.points),
                ),
            )
            insertEffortsAndRefresh(circuitId, trackId, name, slices, now)
            circuitId
        }

    /** Appends the laps of a freshly-recorded [trackId] as efforts of [circuitId] (attempt at the circuit). */
    suspend fun addEffortsFromTrack(circuitId: Long, trackId: Long, name: String, now: Long) =
        withContext(Dispatchers.IO) {
            val slices = timedSlices(trackId)
            if (slices.isNotEmpty()) insertEffortsAndRefresh(circuitId, trackId, name, slices, now)
        }
}
