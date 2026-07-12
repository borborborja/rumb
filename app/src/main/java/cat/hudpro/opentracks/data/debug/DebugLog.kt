package cat.hudpro.opentracks.data.debug

import android.os.Build
import android.os.Process
import android.util.Log
import cat.hudpro.opentracks.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app debug recorder. Every event is mirrored to android.util.Log (so it shows in the process
 * logcat too) and kept in a ring buffer. [logcatDump] reads the whole process logcat — which includes
 * MapLibre native logs, uncaught exceptions and our own events — so the debug screen can show
 * "everything that happens inside the app" without a computer. Reading our own logcat needs no
 * permission (an app only sees its own UID's logs).
 */
object DebugLog {

    private const val TAG = "HUDpro"
    private const val MAX = 4000

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Installs an uncaught-exception hook and logs device/app info. Call once from Application. */
    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            e("CRASH", "Uncaught a ${t.name}", ex)
            prev?.uncaughtException(t, ex)
        }
        i(
            "App",
            "== HUD Pro v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "Android ${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL} ==",
        )
    }

    fun d(sub: String, msg: String) = add('D', sub, msg)
    fun i(sub: String, msg: String) = add('I', sub, msg)
    fun w(sub: String, msg: String) = add('W', sub, msg)
    fun e(sub: String, msg: String, tr: Throwable? = null) =
        add('E', sub, msg + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""))

    private fun add(level: Char, sub: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$sub: $msg"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX) buffer.removeFirst()
        }
        val prio = when (level) {
            'E' -> Log.ERROR; 'W' -> Log.WARN; 'D' -> Log.DEBUG; else -> Log.INFO
        }
        runCatching { Log.println(prio, "$TAG.$sub", msg) }
    }

    /** Our own recorded events, newest last. */
    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) }
    }

    /** Full process logcat (our events + MapLibre native + crashes). Falls back to our buffer. */
    fun logcatDump(): String = runCatching {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"),
        )
        proc.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat no disponible (${it.message}). Events propis:\n\n${snapshot()}" }
}
