package cat.rumb.app.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cat.rumb.app.data.gpx.formatFor
import cat.rumb.app.data.gpx.mimeFor
import cat.rumb.app.data.prefs.FolderExportPreferences
import cat.rumb.app.data.tracks.SyncService
import cat.rumb.app.data.tracks.SyncState
import cat.rumb.app.data.tracks.SyncStatusStore
import java.io.File

/** Saves a GPX file into the user-chosen SAF folder. No network needed. */
class FolderSaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val fileName = inputData.getString(KEY_NAME) ?: "track.gpx"
        val trackId = inputData.getLong(KEY_TRACK_ID, 0L)
        val file = File(path)
        if (!file.exists()) return fail(trackId, "Fitxer no trobat")

        val treeUri = FolderExportPreferences.get(applicationContext).treeUri
            ?: return fail(trackId, "Sense carpeta")
        return try {
            val dir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri))
                ?: return fail(trackId, "Carpeta no accessible")
            // Overwrite a same-named file so a re-sync doesn't accumulate duplicates.
            dir.findFile(fileName)?.delete()
            // MIME must match the actual format (TCX/GPX/KML), else SAF appends a wrong extension
            // (e.g. «x.tcx.gpx») and the dedup findFile() above never matches → duplicates pile up.
            val doc = dir.createFile(mimeFor(formatFor(fileName)), fileName)
                ?: return fail(trackId, "No s'ha pogut crear el fitxer")
            applicationContext.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(file.readText().toByteArray())
            } ?: return fail(trackId, "No s'ha pogut escriure")
            file.delete()
            SyncStatusStore.mark(applicationContext, trackId, SyncService.FOLDER, SyncState.UPLOADED, remoteRef = doc.uri.toString())
            Result.success()
        } catch (e: Exception) {
            fail(trackId, e.message ?: "Error")
        }
    }

    private suspend fun fail(trackId: Long, msg: String): Result {
        SyncStatusStore.mark(applicationContext, trackId, SyncService.FOLDER, SyncState.FAILED, error = msg)
        return Result.failure()
    }

    companion object {
        const val KEY_PATH = "gpx_path"
        const val KEY_NAME = "gpx_name"
        const val KEY_TRACK_ID = "track_id"

        fun enqueue(context: Context, gpx: String, fileName: String, trackId: Long = 0L): File {
            val dir = File(context.cacheDir, "folder_queue").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.gpx")
            file.writeText(gpx)
            val request = OneTimeWorkRequestBuilder<FolderSaveWorker>()
                .setInputData(workDataOf(KEY_PATH to file.absolutePath, KEY_NAME to fileName, KEY_TRACK_ID to trackId))
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return file
        }
    }
}
