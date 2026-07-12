package cat.hudpro.opentracks.data.opentracks

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Controls OpenTracks recording through its Public API (exported activities). Only START and STOP are
 * available externally — OpenTracks has no public pause/resume. The user must enable "Public API" in
 * OpenTracks settings, otherwise these intents are silently ignored.
 *
 * See OpenTracks `de.dennisguse.opentracks.publicapi.StartRecording` / `StopRecording`.
 */
object OpenTracksRecording {

    private const val START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val STOP = "de.dennisguse.opentracks.publicapi.StopRecording"
    private val PACKAGES = listOf("de.dennisguse.opentracks", "de.dennisguse.opentracks.debug")

    private const val EXTRA_STATS_PACKAGE = "STATS_TARGET_PACKAGE"
    private const val EXTRA_STATS_CLASS = "STATS_TARGET_CLASS"

    /** The installed OpenTracks package (release or debug), or null if not installed. */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun isInstalled(context: Context) = installedPackage(context) != null

    /**
     * Starts a new OpenTracks recording and asks it to route the live track back to our viewer
     * (so the dashboard opens automatically). Returns false if it couldn't be launched.
     */
    fun start(context: Context): Boolean = tryLaunch(context) { pkg ->
        Intent().apply {
            setClassName(pkg, START)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_STATS_PACKAGE, context.packageName)
            putExtra(EXTRA_STATS_CLASS, "cat.hudpro.opentracks.viewer.MapViewerActivity")
        }
    }

    /** Stops/finishes the current OpenTracks recording. */
    fun stop(context: Context): Boolean = tryLaunch(context) { pkg ->
        Intent().apply {
            setClassName(pkg, STOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Tries each candidate OpenTracks package; toasts guidance if none can be launched. */
    private fun tryLaunch(context: Context, intentFor: (String) -> Intent): Boolean {
        for (pkg in PACKAGES) {
            val launched = runCatching { context.startActivity(intentFor(pkg)); true }.getOrDefault(false)
            if (launched) return true
        }
        val msg = if (isInstalled(context)) {
            "OpenTracks trobat, però activa l'«API pública» als seus ajustos"
        } else {
            "Instal·la OpenTracks i activa l'«API pública» als seus ajustos"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        return false
    }
}
