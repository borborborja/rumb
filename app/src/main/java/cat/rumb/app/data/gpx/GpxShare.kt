package cat.rumb.app.data.gpx

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Track file formats the app can import. */
enum class TrackFormat { GPX, KML, KMZ, TCX, UNSUPPORTED }

/** Detects the import format from a file/display name (extension-based). Pure. */
fun formatFor(fileName: String?): TrackFormat = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
    "gpx" -> TrackFormat.GPX
    "kml" -> TrackFormat.KML
    "kmz" -> TrackFormat.KMZ
    "tcx" -> TrackFormat.TCX
    else -> TrackFormat.UNSUPPORTED
}

/** MIME type for a track [format] (for the share intent). */
fun mimeFor(format: TrackFormat): String = when (format) {
    TrackFormat.GPX -> "application/gpx+xml"
    TrackFormat.TCX -> "application/vnd.garmin.tcx+xml"
    TrackFormat.KML -> "application/vnd.google-earth.kml+xml"
    TrackFormat.KMZ -> "application/vnd.google-earth.kmz"
    TrackFormat.UNSUPPORTED -> "application/octet-stream"
}

/**
 * Detects a track format from the first bytes of the file (for shared Uris that arrive as
 * `application/octet-stream` with no usable extension). PK-zip → KMZ; otherwise the first XML root tag.
 */
fun sniffFormat(head: ByteArray): TrackFormat {
    if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return TrackFormat.KMZ
    val text = String(head, Charsets.UTF_8)
    return when {
        text.contains("<gpx") -> TrackFormat.GPX
        text.contains("<TrainingCenterDatabase") -> TrackFormat.TCX
        text.contains("<kml") -> TrackFormat.KML
        else -> TrackFormat.UNSUPPORTED
    }
}

/**
 * Resolves a filename with a supported extension for an incoming (shared/opened) Uri, so
 * [formatFor] can detect the format. Uses [displayName] when its extension is known; otherwise
 * sniffs the first bytes and synthesizes `shared.<ext>`. Returns null if the content isn't a track.
 */
fun resolveTrackFileName(resolver: android.content.ContentResolver, uri: android.net.Uri, displayName: String?): String? {
    if (formatFor(displayName) != TrackFormat.UNSUPPORTED) return displayName
    val head = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64)
            val n = input.read(buf)
            if (n > 0) buf.copyOf(n) else ByteArray(0)
        }
    }.getOrNull() ?: return null
    val ext = when (sniffFormat(head)) {
        TrackFormat.GPX -> "gpx"
        TrackFormat.TCX -> "tcx"
        TrackFormat.KML -> "kml"
        TrackFormat.KMZ -> "kmz"
        TrackFormat.UNSUPPORTED -> return null
    }
    return (displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "shared") + ".$ext"
}

/** Shares a track file via the system chooser (save to Files/Drive, send to apps…). */
object GpxShare {

    /** Legacy GPX one-shot (kept for callers passing pre-serialized GPX text). */
    suspend fun share(context: Context, name: String, gpx: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "track" }
        shareFile(context, "$safe.gpx", gpx, "application/gpx+xml", name)
    }

    /** Writes [content] to the share cache as [fileName] and fires the chooser with [mime]. */
    suspend fun shareFile(context: Context, fileName: String, content: String, mime: String, subject: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, fileName).also { it.writeText(content) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(cat.rumb.app.R.string.share_export_title, subject)))
    }
}
