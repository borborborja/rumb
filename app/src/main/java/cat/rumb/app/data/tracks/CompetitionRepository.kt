package cat.rumb.app.data.tracks

import cat.rumb.app.data.competition.GhostEngine
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Unified competitions (leaderboards of attempts you try to beat). ROUTE = whole tracks; LAP = laps
 * of a fixed circuit. Reference and attempts keep GPX inline, so the analysis survives deletion or
 * editing of the source track.
 */
class CompetitionRepository(
    private val dao: CompetitionDao,
    private val trackRepository: TrackRepository,
) {
    fun observeCompetitions(): Flow<List<CompetitionEntity>> = dao.observeCompetitions()
    fun attemptsFor(competitionId: Long): Flow<List<CompetitionAttemptEntity>> = dao.attemptsFor(competitionId)
    suspend fun attemptsOnce(competitionId: Long): List<CompetitionAttemptEntity> = dao.attemptsForOnce(competitionId)
    fun observeSourceTrackIds(): Flow<List<Long>> = dao.observeSourceTrackIds()
    suspend fun getCompetition(id: Long): CompetitionEntity? = dao.getCompetition(id)
    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun setArchived(id: Long, flag: Boolean) = dao.setArchived(id, flag)
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteAttempts(id); dao.deleteCompetition(id) }

    /** A lap slice with its computed time, ready to become a LAP attempt or the reference. */
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

    private suspend fun timedSlices(trackId: Long): List<LapSlice> {
        val pts = trackRepository.loadGpxRoute(trackId)
        val laps = Laps.decode(trackRepository.get(trackId)?.laps)
        return sliceLaps(pts, laps).filter { it.timeMs > 0 && GhostEngine.isTimed(it.points) }
    }

    /** Fastest positive attempt becomes the ghost/reference; the frozen line (LAP) never changes. */
    private suspend fun refreshReference(competitionId: Long, name: String) {
        val best = dao.attemptsForOnce(competitionId).filter { it.timeMs > 0 }.minByOrNull { it.timeMs }
        if (best != null) dao.updateReference(competitionId, best.gpx, best.id)
    }

    private suspend fun insertLapAttempts(competitionId: Long, trackId: Long, name: String, slices: List<LapSlice>, now: Long) {
        slices.forEach { s ->
            dao.insertAttempt(
                CompetitionAttemptEntity(
                    competitionId = competitionId, sourceTrackId = trackId, lapIndex = s.lapIndex,
                    timeMs = s.timeMs, distanceM = s.stats.distanceM, avgHr = s.stats.avgHr,
                    createdAt = now, gpx = Gpx.write(name, s.points),
                ),
            )
        }
        refreshReference(competitionId, name)
    }

    private suspend fun insertRouteAttempt(competitionId: Long, trackId: Long, name: String, pts: List<GpxPoint>, now: Long) {
        val stats = TrackStatsCalculator.compute(pts)
        dao.insertAttempt(
            CompetitionAttemptEntity(
                competitionId = competitionId, sourceTrackId = trackId, lapIndex = -1,
                timeMs = TrackRepository.durationMs(pts), distanceM = stats.distanceM, avgHr = stats.avgHr,
                createdAt = now, gpx = Gpx.write(name, pts),
            ),
        )
        refreshReference(competitionId, name)
    }

    /**
     * Creates a competition from [trackId]. LAP: the fastest lap is the reference (line + ghost) and
     * every lap becomes an attempt. ROUTE: the whole track is the reference and its first attempt.
     * Returns the new competition id, or null when the source can't act as a reference.
     */
    suspend fun createFromTrack(trackId: Long, name: String, activityType: String?, type: String, now: Long): Long? =
        withContext(Dispatchers.IO) {
            if (type == CompetitionType.LAP) {
                val slices = timedSlices(trackId)
                if (slices.isEmpty()) return@withContext null
                val parent = slices.minByOrNull { it.timeMs }!!
                val line = parent.points.first()
                val id = dao.insertCompetition(
                    CompetitionEntity(
                        name = name, type = CompetitionType.LAP, activityType = activityType, createdAt = now,
                        referenceGpx = Gpx.write(name, parent.points),
                        lineLat = line.latitude, lineLng = line.longitude,
                        radiusM = 25.0, minLapMs = 20_000, minLapM = 100.0,
                    ),
                )
                insertLapAttempts(id, trackId, name, slices, now)
                id
            } else {
                val pts = trackRepository.loadGpxRoute(trackId)
                if (!GhostEngine.isTimed(pts)) return@withContext null
                val id = dao.insertCompetition(
                    CompetitionEntity(
                        name = name, type = CompetitionType.ROUTE, activityType = activityType, createdAt = now,
                        referenceGpx = Gpx.write(name, pts),
                    ),
                )
                insertRouteAttempt(id, trackId, name, pts, now)
                id
            }
        }

    /** Files a freshly-recorded [trackId] as attempt(s) of [competitionId] (branches on its type). */
    suspend fun addAttemptsFromTrack(competitionId: Long, trackId: Long, name: String, now: Long) =
        withContext(Dispatchers.IO) {
            val comp = dao.getCompetition(competitionId) ?: return@withContext
            if (comp.type == CompetitionType.LAP) {
                val slices = timedSlices(trackId)
                if (slices.isNotEmpty()) insertLapAttempts(competitionId, trackId, name, slices, now)
            } else {
                val pts = trackRepository.loadGpxRoute(trackId)
                if (GhostEngine.isTimed(pts)) insertRouteAttempt(competitionId, trackId, name, pts, now)
            }
        }
}
