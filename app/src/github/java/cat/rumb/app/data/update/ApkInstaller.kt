package cat.rumb.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Downloads an APK update and launches the system installer. */
object ApkInstaller {

    private val client = OkHttpClient()

    /**
     * Downloads [url] to the app cache, reporting progress in [0,1]. Returns the file.
     * [onProgress] is suspending so a worker can publish progress / update its notification from it.
     */
    suspend fun download(context: Context, url: String, onProgress: suspend (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.externalCacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "Rumb-update.apk")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Resposta buida")
                val total = body.contentLength().takeIf { it > 0 }
                file.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total != null) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
            }
            file
        }

    /** True if the app is allowed to install packages (API 26+ per-app permission). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the per-app "install unknown apps" settings screen. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Launches the system installer for [apk] via a FileProvider content URI. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
