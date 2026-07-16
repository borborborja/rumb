package cat.rumb.app.data.tracks

import cat.rumb.app.data.competition.GhostEngine
import cat.rumb.app.data.debug.DebugLog
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.viewer.hud.MetricsCalculator
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
    /** Custom activity types (from prefs) so their family can be resolved. */
    private val customTypes: () -> List<CustomActivityType> = { emptyList() },
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

    /**
     * Whether a ROUTE recording actually raced the route, so it may join the leaderboard. Without
     * this gate ANY timed recording became an attempt — and since [refreshReference] promotes the
     * FASTEST attempt, abandoning a route after two minutes would win and replace the ghost.
     * A valid attempt covers most of the reference distance AND ends at the reference finish.
     */
    private fun isValidRouteAttempt(refPts: List<GpxPoint>, pts: List<GpxPoint>, attemptDistM: Double): Boolean {
        if (pts.size < 2) return false
        // No usable reference (legacy/degenerate competition): can't judge, so don't block.
        if (refPts.size < 2) return true
        val refDistM = TrackStatsCalculator.compute(refPts).distanceM
        if (refDistM <= 0.0) return true
        if (attemptDistM < refDistM * MIN_ROUTE_COVERAGE) {
            DebugLog.i("Competi", "intent ROUTE descartat · ${fmtM(attemptDistM)}/${fmtM(refDistM)} m recorreguts")
            return false
        }
        val toFinishM = MetricsCalculator.distanceMeters(pts.last().toGeoPoint(), refPts.last().toGeoPoint())
        if (toFinishM > FINISH_RADIUS_M) {
            DebugLog.i("Competi", "intent ROUTE descartat · acaba a ${fmtM(toFinishM)} m de la meta")
            return false
        }
        return true
    }

    private fun fmtM(v: Double) = "%.0f".format(v)

    /** Length of a lap of this circuit, from its frozen reference. 0 = no usable reference. */
    private fun referenceLapDistanceM(comp: CompetitionEntity): Double {
        val refPts = runCatching { Gpx.read(comp.referenceGpx.byteInputStream()).points }
            .getOrDefault(emptyList())
        if (refPts.size < 2) return 0.0
        return TrackStatsCalculator.compute(refPts).distanceM
    }

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
        val attempts = dao.attemptsForOnce(competitionId).filter { it.timeMs > 0 }
        if (attempts.isEmpty()) return
        // Defensive: only compare attempts that cover a similar distance, so a much shorter one
        // (e.g. an abandoned run filed by a build from before ROUTE attempts were validated) can't
        // win the ghost just for being "fastest". Same-length laps are unaffected.
        val longestM = attempts.maxOf { it.distanceM }
        val comparable = attempts.filter { it.distanceM >= longestM * MIN_ROUTE_COVERAGE }
        val best = (comparable.ifEmpty { attempts }).minByOrNull { it.timeMs } ?: return
        dao.updateReference(competitionId, best.gpx, best.id)
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

    /** Why a recording did or didn't join a competition's leaderboard. */
    enum class AttemptOutcome { FILED, WRONG_SPORT, SIMULATED, ROUTE_NOT_RACED, LAP_NOT_RACED, NO_LAP, NOT_TIMED, NO_COMPETITION }

    data class AttemptResult(val outcome: AttemptOutcome, val filed: Int = 0)

    /**
     * Files a freshly-recorded [trackId] as attempt(s) of [competitionId] (branches on its type),
     * reporting WHY when it doesn't qualify so the caller can say so.
     *
     * The sport gate lives HERE and not in a screen on purpose: the desktop server creates
     * competitions over HTTP too, and a rule living in the UI would simply be bypassed.
     */
    suspend fun addAttemptsFromTrack(competitionId: Long, trackId: Long, name: String, now: Long): AttemptResult =
        withContext(Dispatchers.IO) {
            val comp = dao.getCompetition(competitionId)
                ?: return@withContext AttemptResult(AttemptOutcome.NO_COMPETITION)
            val track = trackRepository.get(trackId)
            // A replayed track is not an effort — it would be the fastest attempt ever recorded.
            if (track?.source == TrackSource.SIMULATED) {
                DebugLog.i("Competi", "intent descartat · track simulat")
                return@withContext AttemptResult(AttemptOutcome.SIMULATED)
            }
            // Same-family check. Ground truth is the SAVED activity type (the save dialog forces a
            // choice), not any live "current sport" — we can't stop someone mislabelling a track.
            val trackType = track?.activityType
            val compType = backfilledActivityType(comp)
            if (!ActivityTypes.comparableTypes(trackType, compType, customTypes())) {
                DebugLog.i("Competi", "intent descartat · esport $trackType ≠ competició $compType")
                return@withContext AttemptResult(AttemptOutcome.WRONG_SPORT)
            }
            if (comp.type == CompetitionType.LAP) {
                val all = timedSlices(trackId)
                if (all.isEmpty()) return@withContext AttemptResult(AttemptOutcome.NO_LAP)
                // Same idea as isValidRouteAttempt, which LAP never had: a lap you gave up is not a
                // lap. The recorder already refuses to close one (and marks it ABORTED), but tracks
                // also arrive from imports and from the desktop server, which bypass the recorder.
                val refLapM = referenceLapDistanceM(comp)
                val slices = if (refLapM > 0) all.filter { it.stats.distanceM >= refLapM * LAP_MIN_COVERAGE } else all
                if (slices.isEmpty()) {
                    DebugLog.i("Competi", "intents LAP descartats · cap volta cobreix ${fmtM(refLapM * LAP_MIN_COVERAGE)} m")
                    return@withContext AttemptResult(AttemptOutcome.LAP_NOT_RACED)
                }
                insertLapAttempts(competitionId, trackId, name, slices, now)
                AttemptResult(AttemptOutcome.FILED, slices.size)
            } else {
                val pts = trackRepository.loadGpxRoute(trackId)
                if (!GhostEngine.isTimed(pts)) return@withContext AttemptResult(AttemptOutcome.NOT_TIMED)
                val refPts = runCatching { Gpx.read(comp.referenceGpx.byteInputStream()).points }
                    .getOrDefault(emptyList())
                val distM = TrackStatsCalculator.compute(pts).distanceM
                if (!isValidRouteAttempt(refPts, pts, distM)) {
                    return@withContext AttemptResult(AttemptOutcome.ROUTE_NOT_RACED)
                }
                insertRouteAttempt(competitionId, trackId, name, pts, now)
                AttemptResult(AttemptOutcome.FILED, 1)
            }
        }

    /**
     * Recovers and persists the sport of [competitionId] if it predates the field being stored.
     * Call it when showing a competition: the recovery in [addAttemptsFromTrack] is lazy, so without
     * this an old competition would show no sport until the next attempt was filed.
     */
    suspend fun ensureActivityType(competitionId: Long): String? = withContext(Dispatchers.IO) {
        val comp = dao.getCompetition(competitionId) ?: return@withContext null
        backfilledActivityType(comp)
    }

    /**
     * A competition's sport, recovering it for old rows created before it was recorded: fall back to
     * the activity type of an attempt's source track. Persists the recovery so it's paid once.
     * Still null → UNKNOWN, which is permissive (see [ActivityTypes.comparable]).
     */
    private suspend fun backfilledActivityType(comp: CompetitionEntity): String? {
        comp.activityType?.let { return it }
        val fromSource = dao.attemptsForOnce(comp.id)
            .mapNotNull { it.sourceTrackId }
            .firstNotNullOfOrNull { trackRepository.get(it)?.activityType }
            ?: return null
        dao.setActivityType(comp.id, fromSource)
        DebugLog.i("Competi", "esport recuperat per competició ${comp.id} → $fromSource")
        return fromSource
    }

    private companion object {
        /** Share of the reference distance an attempt must cover to count as having raced it. */
        const val MIN_ROUTE_COVERAGE = 0.9
        /** How close to the reference finish an attempt must end (m). */
        const val FINISH_RADIUS_M = 75.0
        /** Same for one lap. Looser than a route: laps are raced by feel around a line, and a lap
         *  starts and ends at the same place, so there is no finish check to back the distance up. */
        const val LAP_MIN_COVERAGE = 0.8
    }
}
