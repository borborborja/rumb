package cat.hudpro.opentracks.data.map

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
import java.io.File
import java.util.UUID

/**
 * Downloads a raster region into an MBTiles file in the background, showing a progress notification,
 * then registers it with [OfflineMapStore] so it appears as an offline map.
 */
class RegionDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: "Àrea offline"
        val minZoom = inputData.getInt(KEY_MIN_ZOOM, 8)
        val maxZoom = inputData.getInt(KEY_MAX_ZOOM, 14)
        val bbox = BoundingBox(
            west = inputData.getDouble(KEY_W, 0.0),
            south = inputData.getDouble(KEY_S, 0.0),
            east = inputData.getDouble(KEY_E, 0.0),
            north = inputData.getDouble(KEY_N, 0.0),
        )
        if (!bbox.isValid) return Result.failure()

        val source = MapSource.byId(sourceId)
        val store = OfflineMapStore.get(applicationContext)
        // One archive per map type, keyed by sourceId so every area of the type accumulates into it
        // (reuse an existing record's file if present, e.g. legacy downloads named by display name).
        val outFile = File(
            store.bySourceId(source.id)?.path
                ?: File(store.mbtilesDir, "offline_${source.id}.mbtiles").absolutePath,
        )

        cat.hudpro.opentracks.data.debug.DebugLog.i(
            "Download", "inici · ${source.id} · zoom $minZoom-$maxZoom · ${TileMath.tileCount(bbox, minZoom, maxZoom)} tessel·les → ${outFile.name}",
        )
        setForeground(foregroundInfo(0, 1))
        return try {
            TileDownloader().download(source, bbox, minZoom, maxZoom, outFile) { p ->
                setProgress(workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_FAILED to p.failed))
                setForeground(foregroundInfo(p.done, p.total))
            }
            cat.hudpro.opentracks.data.debug.DebugLog.i("Download", "completada · ${outFile.name}")
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
                    tileCount = TileMath.tileCount(bbox, minZoom, maxZoom),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            Result.success(workDataOf(KEY_PATH to outFile.absolutePath))
        } catch (e: Exception) {
            cat.hudpro.opentracks.data.debug.DebugLog.e("Download", "error", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error")))
        }
    }

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Descàrrega de mapes", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val max = total.coerceAtLeast(1).toInt()
        val progress = done.coerceAtMost(total).toInt()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Descarregant mapa offline")
            .setContentText("$progress / $max tessel·les")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(max, progress, total <= 1)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_NAME = "name"
        const val KEY_MIN_ZOOM = "min_zoom"
        const val KEY_MAX_ZOOM = "max_zoom"
        const val KEY_W = "w"
        const val KEY_S = "s"
        const val KEY_E = "e"
        const val KEY_N = "n"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val WORK_NAME = "region_download"
        private const val CHANNEL = "map_download"
        private const val NOTIF_ID = 4242

        fun enqueue(
            context: Context,
            sourceId: String,
            name: String,
            bbox: BoundingBox,
            minZoom: Int,
            maxZoom: Int,
        ): UUID {
            val request = OneTimeWorkRequestBuilder<RegionDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SOURCE to sourceId,
                        KEY_NAME to name,
                        KEY_MIN_ZOOM to minZoom,
                        KEY_MAX_ZOOM to maxZoom,
                        KEY_W to bbox.west, KEY_S to bbox.south, KEY_E to bbox.east, KEY_N to bbox.north,
                    ),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request,
            )
            return request.id
        }
    }
}
