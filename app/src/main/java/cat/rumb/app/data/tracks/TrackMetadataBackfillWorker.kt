package cat.rumb.app.data.tracks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cat.rumb.app.BuildConfig
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.geo.GeocodingBatch
import cat.rumb.app.data.geo.NominatimClient
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.prefs.ViewerPreferences
import kotlinx.coroutines.delay

/**
 * Fills in track metadata added in DB v4:
 * 1. ascent + start coordinates for tracks saved before the migration (parsed from the stored GPX);
 * 2. municipality via Nominatim reverse geocoding — serialized here with a >1 s delay per request,
 *    per the OSM usage policy (1 req/s max). Unique work guarantees a single queue.
 */
class TrackMetadataBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = RumbApplication.from(applicationContext).database.followTrackDao()

        // Phase 1 (no network): extract ascent/start from the stored GPX blob.
        for (id in dao.idsNeedingMeta()) {
            val entity = dao.getById(id) ?: continue
            val points = runCatching { entity.gpx.byteInputStream().use { Gpx.read(it) }.points }
                .getOrDefault(emptyList())
            dao.setMeta(
                id,
                TrackRepository.ascent(points),
                points.firstOrNull()?.latitude,
                points.firstOrNull()?.longitude,
            )
        }

        // Phase 1b (no network): total duration for pre-v5 tracks (0 marks untimed, no reprocess).
        for (id in dao.idsNeedingDuration()) {
            val entity = dao.getById(id) ?: continue
            val points = runCatching { entity.gpx.byteInputStream().use { Gpx.read(it) }.points }
                .getOrDefault(emptyList())
            dao.setDuration(id, TrackRepository.durationMs(points))
        }

        // Phase 2 (network): explicit opt-in is required before any coordinates leave the device.
        val prefs = ViewerPreferences.get(applicationContext)
        if (!prefs.onlineGeocodingEnabled) return Result.success()
        val pending = dao.needingMunicipality()
        if (pending.isEmpty()) return Result.success()
        val client = NominatimClient(
            "Rumb/${BuildConfig.VERSION_NAME} (github.com/borborborja/rumb)",
        )
        var networkFailures = 0
        GeocodingBatch.run(pending, isEnabled = { prefs.onlineGeocodingEnabled }) { item ->
            when (val r = client.reverse(item.startLat, item.startLon)) {
                is NominatimClient.Reverse.Ok ->
                    // Write the name, or the "checked, no place" sentinel ("") so we never re-query it.
                    dao.setMunicipality(item.id, r.name ?: MUNICIPALITY_CHECKED_NONE)
                // A permanent 4xx (e.g. 403 policy block) will never resolve: mark checked so the row
                // leaves the queue instead of forcing an unbounded retry loop that re-hits Nominatim.
                NominatimClient.Reverse.PermanentFail -> dao.setMunicipality(item.id, MUNICIPALITY_CHECKED_NONE)
                NominatimClient.Reverse.Failed -> networkFailures++ // transient → retry the run
            }
            delay(1100)
        }
        // Retry transient failures, but cap attempts so a persistently unreachable Nominatim can't
        // reschedule forever; leftover rows stay NULL and are retried on the next enqueue (next import).
        return if (prefs.onlineGeocodingEnabled && networkFailures > 0 && runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "track_metadata_backfill"
        /** Cap on WorkManager retries for transient geocoding failures (backoff would run forever). */
        private const val MAX_RETRY_ATTEMPTS = 5
        /** Sentinel written to `municipality` when reverse-geocoding succeeded but found no place. */
        const val MUNICIPALITY_CHECKED_NONE = ""

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackMetadataBackfillWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
