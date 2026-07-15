package cat.rumb.app.data.endurain

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.tracks.SyncService
import cat.rumb.app.data.tracks.SyncState
import cat.rumb.app.data.tracks.SyncStatusStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads a GPX file to Endurain with retry, recording the outcome in the sync outbox. The GPX is
 * written to the app's cache first (WorkManager Data has a ~10KB limit, too small for a track), and
 * its path is passed via inputData.
 */
class EndurainUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val fileName = inputData.getString(KEY_NAME) ?: "track.gpx"
        val trackId = inputData.getLong(KEY_TRACK_ID, 0L)
        val file = File(path)
        if (!file.exists()) return fail(trackId, "Fitxer no trobat")

        val repo = RumbApplication.from(applicationContext).endurainRepository
        return when (val result = repo.uploadGpx(file.readText(), fileName)) {
            is UploadResult.Success -> {
                file.delete()
                SyncStatusStore.mark(
                    applicationContext, trackId, SyncService.ENDURAIN, SyncState.UPLOADED,
                    remoteRef = result.activityIds.firstOrNull()?.toString(),
                )
                Result.success(workDataOf(KEY_RESULT to "ok:${result.activityIds.joinToString(",")}"))
            }
            is UploadResult.NotConfigured -> {
                file.delete() // permanent: don't leave the queued GPX orphaned in the cache.
                fail(trackId, "Endurain no configurat")
            }
            is UploadResult.Failure -> {
                // Retry transient errors (network code==null, 5xx, 429), but cap attempts so a
                // persistently-failing server or a permanent misconfig can't retry forever.
                val transient = result.code == null || result.code in 500..599 || result.code == 429
                if (transient && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    file.delete()
                    SyncStatusStore.mark(
                        applicationContext, trackId, SyncService.ENDURAIN, SyncState.FAILED,
                        error = result.code?.let { "HTTP $it" } ?: result.message,
                    )
                    Result.failure(workDataOf(KEY_RESULT to "http:${result.code}"))
                }
            }
        }
    }

    private suspend fun fail(trackId: Long, msg: String): Result {
        SyncStatusStore.mark(applicationContext, trackId, SyncService.ENDURAIN, SyncState.FAILED, error = msg)
        return Result.failure()
    }

    companion object {
        const val KEY_PATH = "gpx_path"
        const val KEY_NAME = "gpx_name"
        const val KEY_TRACK_ID = "track_id"
        const val KEY_RESULT = "result"
        private const val MAX_RETRY_ATTEMPTS = 8

        /** Writes [gpx] to cache and enqueues an upload. [trackId] &lt;= 0 skips status tracking. */
        fun enqueue(context: Context, gpx: String, fileName: String, trackId: Long = 0L): File {
            val dir = File(context.cacheDir, "endurain_queue").apply { mkdirs() }
            val unique = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
            val file = File(dir, "$unique.gpx")
            file.writeText(gpx)
            val data: Data = workDataOf(KEY_PATH to file.absolutePath, KEY_NAME to fileName, KEY_TRACK_ID to trackId)
            val request = OneTimeWorkRequestBuilder<EndurainUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return file
        }
    }
}
