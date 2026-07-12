package cat.hudpro.opentracks.data.opentracks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * Controls OpenTracks recording through its Public API (exported activities). Only START and STOP are
 * available externally — OpenTracks has no public pause/resume. The user must enable "Public API" in
 * OpenTracks settings, otherwise these intents are silently ignored.
 *
 * The OpenTracks package/applicationId varies by build (release `de.dennisguse.opentracks`, nightly
 * `.debug`, and forks with other ids), so instead of assuming a name we DISCOVER the installed
 * package that exposes an activity ending in `.publicapi.StartRecording`.
 */
object OpenTracksRecording {

    private const val START_SUFFIX = ".publicapi.StartRecording"
    private const val STOP_SUFFIX = ".publicapi.StopRecording"

    private const val EXTRA_STATS_PACKAGE = "STATS_TARGET_PACKAGE"
    private const val EXTRA_STATS_CLASS = "STATS_TARGET_CLASS"

    /** The OpenTracks public recording API found on the device, or null if none. */
    data class RecordingApi(val pkg: String, val startClass: String, val stopClass: String)

    fun discover(context: Context): RecordingApi? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = runCatching { pm.getInstalledPackages(PackageManager.GET_ACTIVITIES) }.getOrNull().orEmpty()
        for (info in packages) {
            val activities = info.activities ?: continue
            val start = activities.firstOrNull { it.name.endsWith(START_SUFFIX) } ?: continue
            val stop = activities.firstOrNull { it.name.endsWith(STOP_SUFFIX) }?.name
                ?: start.name.removeSuffix("StartRecording") + "StopRecording"
            return RecordingApi(info.packageName, start.name, stop)
        }
        return null
    }

    fun isInstalled(context: Context) = discover(context) != null

    fun start(context: Context): Boolean {
        val api = discover(context) ?: run { notFound(context); return false }
        return launch(context, api.pkg, api.startClass) {
            putExtra(EXTRA_STATS_PACKAGE, context.packageName)
            putExtra(EXTRA_STATS_CLASS, "cat.hudpro.opentracks.viewer.MapViewerActivity")
        }
    }

    fun stop(context: Context): Boolean {
        val api = discover(context) ?: run { notFound(context); return false }
        return launch(context, api.pkg, api.stopClass) {}
    }

    private fun launch(context: Context, pkg: String, cls: String, extras: Intent.() -> Unit): Boolean {
        val intent = Intent().apply {
            setClassName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras()
        }
        return runCatching { context.startActivity(intent); true }.getOrElse {
            cat.hudpro.opentracks.data.debug.DebugLog.e("Record", "launch $pkg/$cls fallit", it)
            Toast.makeText(context, "No s'ha pogut obrir OpenTracks. Activa l'«API pública» als seus ajustos.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun notFound(context: Context) {
        Toast.makeText(context, "No s'ha trobat OpenTracks amb l'API pública. Instal·la'l i activa-la.", Toast.LENGTH_LONG).show()
    }

    /** Detection-only report (no side effects): visible OpenTracks-like packages and the public API. */
    fun detectionReport(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        @Suppress("DEPRECATION")
        val all = runCatching { pm.getInstalledPackages(0) }.getOrNull().orEmpty()
        val matches = all.map { it.packageName }.filter {
            it.contains("track", true) || it.contains("dennisguse", true) || it.contains("opentracks", true)
        }
        sb.appendLine("Paquets 'track/opentracks': ${if (matches.isEmpty()) "cap" else matches.joinToString()}")
        val api = discover(context)
        if (api == null) {
            sb.append("API pública OpenTracks: NO trobada")
        } else {
            sb.appendLine("API pública: ${api.pkg}")
            sb.append("  start: ${api.startClass}")
        }
        return sb.toString().trim()
    }

    /** Debug report: what OpenTracks-like packages are visible and whether the public API is found. */
    fun diagnostics(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        sb.appendLine("El nostre paquet: ${context.packageName}")

        @Suppress("DEPRECATION")
        val all = runCatching { pm.getInstalledPackages(0) }.getOrNull().orEmpty()
        sb.appendLine("Paquets instal·lats visibles: ${all.size}")
        val matches = all.map { it.packageName }.filter {
            it.contains("track", true) || it.contains("dennisguse", true) || it.contains("opentracks", true)
        }
        sb.appendLine("Coincidències 'track/opentracks': ${if (matches.isEmpty()) "cap" else matches.joinToString()}")

        val api = discover(context)
        if (api == null) {
            sb.appendLine("API pública OpenTracks: NO trobada")
        } else {
            sb.appendLine("API pública trobada:")
            sb.appendLine("  paquet: ${api.pkg}")
            sb.appendLine("  start: ${api.startClass}")
            sb.appendLine("  stop: ${api.stopClass}")
            val result = runCatching {
                context.startActivity(
                    Intent().apply {
                        setClassName(api.pkg, api.startClass)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(EXTRA_STATS_PACKAGE, context.packageName)
                        putExtra(EXTRA_STATS_CLASS, "cat.hudpro.opentracks.viewer.MapViewerActivity")
                    },
                )
            }
            sb.appendLine(
                if (result.isSuccess) "  → LLANÇAT ✓ (si no grava, activa l'API pública a OpenTracks)"
                else "  → ERROR: ${result.exceptionOrNull()?.message}",
            )
        }
        return sb.toString().trim()
    }
}
