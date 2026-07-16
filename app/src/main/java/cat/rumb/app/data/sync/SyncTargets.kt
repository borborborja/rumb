package cat.rumb.app.data.sync

import android.content.Context
import cat.rumb.app.data.endurain.EndurainUploadWorker
import cat.rumb.app.data.prefs.EndurainPreferences
import cat.rumb.app.data.prefs.FolderExportPreferences
import cat.rumb.app.data.prefs.WebDavPreferences
import cat.rumb.app.data.tracks.SyncService
import cat.rumb.app.data.tracks.SyncState
import cat.rumb.app.data.tracks.SyncStatusStore
import kotlinx.coroutines.flow.first

/**
 * Fans a saved track out to every configured sync target (Endurain, WebDAV, folder). Each target
 * marks itself PENDING and enqueues its own worker, which later records UPLOADED/FAILED in the outbox.
 */
object SyncTargets {

    /** True when at least one target is configured (so the UI can show sync affordances). */
    fun anyConfigured(context: Context): Boolean =
        EndurainPreferences.get(context).isConfigured ||
            WebDavPreferences.get(context).isConfigured ||
            FolderExportPreferences.get(context).isEnabled

    /**
     * Enqueues [gpx] to all configured targets. [trackId] links status back to the training (0 to skip
     * status tracking, e.g. the OpenTracks path). [fileName] should already end in .gpx.
     */
    suspend fun enqueueAll(context: Context, trackId: Long, fileName: String, gpx: String) {
        if (EndurainPreferences.get(context).isConfigured) {
            SyncStatusStore.mark(context, trackId, SyncService.ENDURAIN, SyncState.PENDING)
            EndurainUploadWorker.enqueue(context, gpx, fileName, trackId)
        }
        if (WebDavPreferences.get(context).isConfigured) {
            SyncStatusStore.mark(context, trackId, SyncService.WEBDAV, SyncState.PENDING)
            WebDavUploadWorker.enqueue(context, gpx, fileName, trackId)
        }
        if (FolderExportPreferences.get(context).isEnabled) {
            SyncStatusStore.mark(context, trackId, SyncService.FOLDER, SyncState.PENDING)
            FolderSaveWorker.enqueue(context, gpx, fileName, trackId)
        }
    }

    /**
     * Enqueues every library track not yet uploaded to Endurain — recorded AND imported — for upload,
     * skipping archived tracks, those without geometry, and those already up (no duplicates). Auto-
     * upload only fires when a recording stops, so this is how pre-existing/imported tracks get sent.
     * Returns how many were queued.
     */
    suspend fun uploadAllPendingToEndurain(context: Context): Int {
        if (!EndurainPreferences.get(context).isConfigured) return 0
        val app = cat.rumb.app.RumbApplication.from(context)
        val summaries = app.trackRepository.observeSummaries().first()
        val alreadyUp = app.database.syncStatusDao().uploadedTrackIds(SyncService.ENDURAIN).toHashSet()
        val up = cat.rumb.app.data.prefs.ViewerPreferences.get(context)
        var queued = 0
        for (s in summaries) {
            if (s.archived || s.id in alreadyUp) continue
            val entity = app.trackRepository.get(s.id) ?: continue
            val points = runCatching { cat.rumb.app.data.gpx.Gpx.read(entity.gpx.byteInputStream()).points }
                .getOrDefault(emptyList())
            if (points.size < 2) continue
            val laps = cat.rumb.app.data.tracks.Laps.decode(entity.laps)
            val built = cat.rumb.app.data.gpx.ActivityFile.build(
                safeName(entity.name), points, laps, entity.activityType, up.userWeightKg, up.userAge, up.userSex,
            )
            SyncStatusStore.mark(context, s.id, SyncService.ENDURAIN, SyncState.PENDING)
            EndurainUploadWorker.enqueue(context, built.content, built.fileName, s.id)
            queued++
        }
        return queued
    }

    /** Re-enqueues every FAILED outbox row (used by "retry failed" in settings). */
    suspend fun retryFailed(context: Context) {
        val app = cat.rumb.app.RumbApplication.from(context)
        val failed = app.database.syncStatusDao().failed()
        for (row in failed) {
            val entity = app.trackRepository.get(row.trackId) ?: continue
            val gpxText = entity.gpx.takeIf { it.isNotBlank() } ?: continue
            val points = runCatching { cat.rumb.app.data.gpx.Gpx.read(gpxText.byteInputStream()).points }
                .getOrDefault(emptyList())
            if (points.isEmpty()) continue
            val laps = cat.rumb.app.data.tracks.Laps.decode(entity.laps)
            val up = cat.rumb.app.data.prefs.ViewerPreferences.get(context)
            val built = cat.rumb.app.data.gpx.ActivityFile.build(
                safeName(entity.name), points, laps, entity.activityType, up.userWeightKg, up.userAge, up.userSex,
            )
            when (row.service) {
                SyncService.ENDURAIN -> {
                    SyncStatusStore.mark(context, row.trackId, SyncService.ENDURAIN, SyncState.PENDING)
                    EndurainUploadWorker.enqueue(context, built.content, built.fileName, row.trackId)
                }
                SyncService.WEBDAV -> {
                    SyncStatusStore.mark(context, row.trackId, SyncService.WEBDAV, SyncState.PENDING)
                    WebDavUploadWorker.enqueue(context, built.content, built.fileName, row.trackId)
                }
                SyncService.FOLDER -> {
                    SyncStatusStore.mark(context, row.trackId, SyncService.FOLDER, SyncState.PENDING)
                    FolderSaveWorker.enqueue(context, built.content, built.fileName, row.trackId)
                }
            }
        }
    }

    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "activitat" }
}
