package cat.rumb.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cat.rumb.app.data.gpx.formatFor
import cat.rumb.app.data.gpx.mimeFor
import cat.rumb.app.data.prefs.WebDavPreferences
import cat.rumb.app.data.tracks.SyncService
import cat.rumb.app.data.tracks.SyncState
import cat.rumb.app.data.tracks.SyncStatusStore
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Uploads a GPX file to a WebDAV collection via HTTP PUT + Basic auth. */
class WebDavUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val fileName = inputData.getString(KEY_NAME) ?: "track.gpx"
        val trackId = inputData.getLong(KEY_TRACK_ID, 0L)
        val file = File(path)
        if (!file.exists()) return fail(trackId, "Fitxer no trobat")

        val prefs = WebDavPreferences.get(applicationContext)
        if (!prefs.isConfigured) { file.delete(); return fail(trackId, "WebDAV no configurat") }

        return try {
            val body = file.readText().toRequestBody(mimeFor(formatFor(fileName)).toMediaType())
            val request = Request.Builder()
                .url("${prefs.url.orEmpty().trimEnd('/')}/$fileName")
                .header("Authorization", Credentials.basic(prefs.user!!, prefs.pass!!))
                .put(body)
                .build()
            OkHttpClient().newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    file.delete()
                    SyncStatusStore.mark(applicationContext, trackId, SyncService.WEBDAV, SyncState.UPLOADED, remoteRef = "${prefs.url}/$fileName")
                    Result.success()
                } else {
                    val transient = resp.code in 500..599 || resp.code == 429
                    if (transient && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        file.delete()
                        SyncStatusStore.mark(applicationContext, trackId, SyncService.WEBDAV, SyncState.FAILED, error = "HTTP ${resp.code}")
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                file.delete()
                SyncStatusStore.mark(applicationContext, trackId, SyncService.WEBDAV, SyncState.FAILED, error = e.message ?: "Error de xarxa")
                Result.failure()
            }
        }
    }

    private suspend fun fail(trackId: Long, msg: String): Result {
        SyncStatusStore.mark(applicationContext, trackId, SyncService.WEBDAV, SyncState.FAILED, error = msg)
        return Result.failure()
    }

    companion object {
        const val KEY_PATH = "gpx_path"
        const val KEY_NAME = "gpx_name"
        const val KEY_TRACK_ID = "track_id"
        private const val MAX_RETRY_ATTEMPTS = 8

        fun enqueue(context: Context, gpx: String, fileName: String, trackId: Long = 0L): File {
            val dir = File(context.cacheDir, "webdav_queue").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.gpx")
            file.writeText(gpx)
            val request = OneTimeWorkRequestBuilder<WebDavUploadWorker>()
                .setInputData(workDataOf(KEY_PATH to file.absolutePath, KEY_NAME to fileName, KEY_TRACK_ID to trackId))
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
