package cat.rumb.app.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Downloads the update APK in the background with a progress notification.
 *
 * It runs as a FOREGROUND worker on purpose: the download used to live in the settings screen's
 * `rememberCoroutineScope`, so leaving the screen cancelled it, and with the screen off the app had
 * no foreground component and Doze throttled the network. A foreground worker survives both, and the
 * unique work lets the UI re-attach to a download already in flight.
 */
class ApkDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        DebugLogI("inici · $url")
        setForeground(foregroundInfo(0))
        return try {
            val file = ApkInstaller.download(applicationContext, url) { p ->
                val pct = (p * 100).toInt().coerceIn(0, 100)
                setProgress(workDataOf(KEY_PROGRESS to pct))
                // Notification updates are cheap but not free: only on a whole-percent change.
                if (pct != lastPct) {
                    lastPct = pct
                    setForeground(foregroundInfo(pct))
                }
            }
            DebugLogI("completada · ${file.name} · ${file.length()} bytes")
            Result.success(workDataOf(KEY_PATH to file.absolutePath))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            cat.rumb.app.data.debug.DebugLog.e("Update", "descàrrega fallida", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error")))
        }
    }

    private var lastPct = -1

    private fun DebugLogI(msg: String) = cat.rumb.app.data.debug.DebugLog.i("Update", msg)

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(cat.rumb.app.R.string.update_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(applicationContext.getString(cat.rumb.app.R.string.update_notif_title))
            .setContentText(applicationContext.getString(cat.rumb.app.R.string.update_notif_progress, pct))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val WORK_NAME = "apk_download"
        private const val CHANNEL = "app_update"
        private const val NOTIF_ID = 4244

        fun enqueue(context: Context, url: String) {
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(workDataOf(KEY_URL to url))
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
