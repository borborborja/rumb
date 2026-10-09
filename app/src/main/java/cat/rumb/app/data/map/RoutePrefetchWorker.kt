package cat.rumb.app.data.map

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.debug.DebugLog
import java.io.File

/**
 * Auto-prefetches the map tiles along a followed route into the persistent offline archive of the
 * current online source, so the whole track is available without coverage. Downloads a corridor of
 * small bounding boxes (not one huge diagonal), reusing [TileDownloader] (which skips tiles already
 * present, so re-following the same route is cheap). Serialized with manual downloads under the shared
 * [RegionDownloadWorker.WORK_NAME]; yields to a user's manual REPLACE download.
 */
class RoutePrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        when (cat.rumb.app.data.premium.premiumWorkAccess(applicationContext)) {
            cat.rumb.app.data.premium.PremiumWorkAccess.ALLOWED -> Unit
            cat.rumb.app.data.premium.PremiumWorkAccess.UNKNOWN -> return Result.retry()
            cat.rumb.app.data.premium.PremiumWorkAccess.DENIED -> return Result.failure(
                workDataOf(RegionDownloadWorker.KEY_ERROR to applicationContext.getString(cat.rumb.app.R.string.premium_required)),
            )
        }
        val sourceId = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val trackId = inputData.getLong(KEY_TRACK, -1L)
        val minZoom = inputData.getInt(KEY_MIN_ZOOM, 12)
        val maxZoom = inputData.getInt(KEY_MAX_ZOOM, 15)
        if (trackId <= 0) return Result.failure()

        val app = RumbApplication.from(applicationContext)
        val points = app.trackRepository.loadGpxRoute(trackId).map { it.toGeoPoint() }
        val boxes = RouteCoverageCalculator.corridorBoxes(points).filter { it.isValid }
        if (boxes.isEmpty()) return Result.success()

        val source = MapSource.byId(sourceId)
        val store = OfflineMapStore.get(applicationContext)
        val outFile = File(
            store.bySourceId(source.id)?.path
                ?: File(store.mbtilesDir, "offline_${source.id}.mbtiles").absolutePath,
        )
        val totals = boxes.map { TileMath.tileCount(it, minZoom, maxZoom) }
        val grandTotal = totals.sum().coerceAtLeast(1)
        var doneBefore = 0L
        DebugLog.i("Prefetch", "ruta $trackId · ${source.id} · ${boxes.size} trams · $grandTotal tessel·les")
        setForeground(foregroundInfo(0, grandTotal))

        return try {
            boxes.forEachIndexed { i, bbox ->
                TileDownloader().download(source, bbox, minZoom, maxZoom, outFile) { p ->
                    val done = doneBefore + p.done
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to grandTotal))
                    setForeground(foregroundInfo(done, grandTotal))
                }
                store.addSector(
                    sourceId = source.id,
                    name = source.displayName,
                    attribution = source.attribution,
                    path = outFile.absolutePath,
                    sector = OfflineSector(
                        id = OfflineSector.idOf(bbox, minZoom, maxZoom),
                        bounds = listOf(bbox.west, bbox.south, bbox.east, bbox.north),
                        minZoom = minZoom,
                        maxZoom = maxZoom,
                        tileCount = totals[i],
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                doneBefore += totals[i]
            }
            DebugLog.i("Prefetch", "ruta $trackId completada")
            Result.success()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            DebugLog.e("Prefetch", "error", e)
            Result.failure()
        }
    }

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, applicationContext.getString(cat.rumb.app.R.string.dl_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val max = total.coerceAtLeast(1).toInt()
        val progress = done.coerceAtMost(total).toInt()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(applicationContext.getString(cat.rumb.app.R.string.prefetch_notif_title))
            .setContentText(applicationContext.getString(cat.rumb.app.R.string.dl_notif_progress, progress, max))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(cat.rumb.app.R.string.maps_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_TRACK = "track"
        const val KEY_MIN_ZOOM = "min_zoom"
        const val KEY_MAX_ZOOM = "max_zoom"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val CHANNEL = "map_download"
        private const val NOTIF_ID = 4244

        /** Enqueues a route prefetch; appends after (does not cancel) any running manual download. */
        fun enqueue(context: Context, trackId: Long, sourceId: String, minZoom: Int, maxZoom: Int) {
            val request = OneTimeWorkRequestBuilder<RoutePrefetchWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TRACK to trackId,
                        KEY_SOURCE to sourceId,
                        KEY_MIN_ZOOM to minZoom,
                        KEY_MAX_ZOOM to maxZoom,
                    ),
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .addTag(RegionDownloadWorker.WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RegionDownloadWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            )
        }
    }
}
