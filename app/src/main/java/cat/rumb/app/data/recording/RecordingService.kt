package cat.rumb.app.data.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.debug.DebugLog
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Locale

/**
 * Foreground service (type=location) hosting the native [TrackRecorder]: keeps GPS alive with the
 * screen off, shows a live-stats notification with pause/stop actions, publishes every update to
 * [NativeRecording] for the viewer, and persists points to Room so a killed process resumes the
 * recording (START_STICKY). Modeled on OpenTracks' TrackRecordingService (Apache-2.0).
 */
class RecordingService : Service() {

    private var recorder: TrackRecorder? = null
    // Swapped for a GpxReplaySource when the debug simulator is on, so the ENTIRE real path
    // (engine → auto-lap → competition → save) is exercised rather than a mock of it.
    private var gps: LocationSource = GpsSource(this)
    private val pressure = PressureSource(this)
    private var ble: cat.rumb.app.data.recording.ble.BleSensorManager? = null
    private var autoPause: AutoPause? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingId: Long? = null
    private var restoring = false
    private var segmentIndex = 0
    private val pendingPoints = ArrayList<RecordingPointEntity>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_PAUSE -> doPause(manual = true)
            ACTION_RESUME -> doResume(manual = true)
            ACTION_STOP -> finish()
            ACTION_LAP -> doLap()
            ACTION_END_LAPS -> doEndLaps()
            ACTION_REFRESH_AUTOPAUSE -> refreshAutoPauseFromPrefs()
            // null action = sticky restart after process death: resume the persisted recording.
            null -> restoreAfterDeath()
        }
        return START_STICKY
    }

    private fun begin() {
        if (recorder != null) return // already recording
        val prefs = ViewerPreferences.get(this)
        val r = TrackRecorder(configFrom(prefs))
        r.start(Instant.now())
        recorder = r
        autoPause = if (prefs.recAutoPause) AutoPause(idleAfterSec = prefs.recAutoPauseSec.toLong()) else null
        segmentIndex = 0
        publish()
        startForegroundWithType()
        acquireWakeLock()
        scope.launch {
            val dao = RumbApplication.from(this@RecordingService).database.recordingDao()
            // Only one recording at a time: clear stale leftovers (e.g. a crash the user ignored).
            dao.activeRecording()?.let { stale -> dao.deletePoints(stale.id); dao.deleteRecording(stale.id) }
            recordingId = dao.insertRecording(RecordingEntity(startedAt = System.currentTimeMillis()))
            // Start sensors only after recordingId exists, so early GPS fixes are persisted for crash
            // recovery (not just held in memory). Sensor registration runs on the main thread.
            withContext(Dispatchers.Main) { startSensors(prefs) }
            startPeriodicFlush()
        }
        DebugLog.i("Record", "gravació nativa iniciada · autoPausa=${prefs.recAutoPause}")
    }

    private fun restoreAfterDeath() {
        // `recorder` is only assigned inside the launch below, so guard synchronously against a second
        // sticky redelivery restoring twice (double GPS/BLE/wakelock, duplicate points).
        if (recorder != null || restoring) return
        restoring = true
        startForegroundWithType() // must enter foreground promptly after onStartCommand
        scope.launch {
            try {
                val prefs = ViewerPreferences.get(this@RecordingService)
                val dao = RumbApplication.from(this@RecordingService).database.recordingDao()
                val active = dao.activeRecording()
                if (active == null) {
                    DebugLog.i("Record", "sticky restart sense gravació activa · aturant")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                val stored = dao.points(active.id)
                val r = TrackRecorder(configFrom(prefs))
                r.restore(stored.toSegments(), Instant.ofEpochMilli(active.startedAt), Instant.now())
                active.laps?.let { raw ->
                    runCatching { LAP_JSON.decodeFromString<List<LapMark>>(raw) }.getOrNull()?.let { r.restoreLaps(it) }
                }
                recorder = r
                recordingId = active.id
                segmentIndex = (stored.maxOfOrNull { it.segment } ?: 0) + 1
                autoPause = if (prefs.recAutoPause) AutoPause(idleAfterSec = prefs.recAutoPauseSec.toLong()) else null
                publish()
                acquireWakeLock()
                startSensors(prefs)
                startPeriodicFlush()
                DebugLog.w("Record", "gravació restaurada després de mort del procés · ${stored.size} punts")
            } finally {
                restoring = false
            }
        }
    }

    private fun startSensors(prefs: ViewerPreferences) {
        val simTrack = prefs.simulateTrackId
        gps = if (simTrack > 0) {
            DebugLog.w("Record", "SIMULACIÓ activa · track=$simTrack · ${prefs.simulateSpeed}x")
            GpxReplaySource(this, simTrack, prefs.simulateSpeed)
        } else {
            GpsSource(this)
        }
        val gpsOk = gps.start(intervalMs = prefs.recGpsIntervalSec.coerceAtLeast(1) * 1000L) { loc ->
            val rec = recorder ?: return@start
            val time = Instant.ofEpochMilli(loc.time)
            val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
            val point = rec.onLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
                time = time,
            )
            if (point != null) {
                recordingId?.let { id ->
                    synchronized(pendingPoints) { pendingPoints.add(RecordingPointEntity.from(id, segmentIndex, point)) }
                }
            }
            // Auto-pause: feed every fix (also while paused, to detect movement again).
            autoPause?.let { ap ->
                when (ap.onFix(GeoPoint(loc.latitude, loc.longitude), speed, time, rec.snapshot(time).isPaused)) {
                    AutoPause.Command.PAUSE -> { doPause(manual = false); DebugLog.i("Record", "auto-pausa") }
                    AutoPause.Command.RESUME -> { doResume(manual = false); DebugLog.i("Record", "auto-reprendre") }
                    AutoPause.Command.NONE -> {}
                }
            }
            publish()
            updateNotification(force = false)
        }
        if (prefs.recBarometer) {
            pressure.start { hPa -> recorder?.onPressure(hPa) }
        }
        val sensors = prefs.bleSensorAddrs
        if (sensors.isNotEmpty()) {
            ble = cat.rumb.app.data.recording.ble.BleSensorManager(
                this,
                onHeartRate = { recorder?.onHeartRate(it) },
                onCadence = { recorder?.onCadence(it) },
                onPower = { recorder?.onPower(it) },
            ).also { it.start(sensors) }
        }
        DebugLog.i("Record", "sensors · gps=$gpsOk · interval=${prefs.recGpsIntervalSec}s · ble=${sensors.size}")
    }

    private fun doPause(manual: Boolean) {
        val r = recorder ?: return
        if (manual) autoPause?.onManualOverride()
        r.pause(Instant.now())
        segmentIndex++
        flushPoints()
        publish()
        updateNotification(force = true)
        if (manual) DebugLog.i("Record", "pausa manual")
    }

    private fun doLap() {
        val r = recorder ?: return
        r.lap(Instant.now())
        persistLaps()
        publish()
        updateNotification(force = true)
    }

    private fun doEndLaps() {
        val r = recorder ?: return
        r.endLaps(Instant.now())
        persistLaps()
        publish()
        updateNotification(force = true)
    }

    /** Persists the current lap boundary marks so they survive a process death mid-recording. */
    private fun persistLaps() {
        val marks = recorder?.snapshot(Instant.now())?.lapMarks ?: return
        val id = recordingId ?: return
        val json = runCatching { LAP_JSON.encodeToString(marks) }.getOrNull()
        scope.launch {
            runCatching { RumbApplication.from(this@RecordingService).database.recordingDao().setLaps(id, json) }
        }
    }

    private fun doResume(manual: Boolean) {
        val r = recorder ?: return
        if (manual) autoPause?.onManualOverride()
        r.resume(Instant.now())
        publish()
        updateNotification(force = true)
        if (manual) DebugLog.i("Record", "reprendre manual")
    }

    private fun finish() {
        gps.stop()
        pressure.stop()
        ble?.stop(); ble = null
        recorder?.let {
            it.stop(Instant.now())
            publish()
            DebugLog.i("Record", "gravació nativa aturada · ${it.snapshot(Instant.now()).points().size} punts")
        }
        val id = recordingId
        val finalBatch = drainPending()
        recorder = null
        recordingId = null
        releaseWakeLock()
        // Persist the final points and mark the recording FINISHED (do NOT delete): the durable copy
        // must survive until the user actually saves. A process death between Stop and Save would
        // otherwise lose the whole track — the viewer only held it in memory. The rows are purged in
        // the viewer after insertRoute commits; an unsaved finished recording is recovered on launch.
        // Tear the service down only after the write, so onDestroy's scope.cancel() can't drop it.
        scope.launch {
            val dao = RumbApplication.from(this@RecordingService).database.recordingDao()
            runCatching {
                if (finalBatch.isNotEmpty()) dao.insertPoints(finalBatch)
                if (id != null) dao.setState(id, "FINISHED")
            }.onFailure { DebugLog.e("Record", "no s'ha pogut finalitzar la gravació a disc", it) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startPeriodicFlush() {
        scope.launch {
            while (isActive && recorder != null) {
                delay(5000)
                flushPoints()
            }
        }
    }

    /** Atomically removes and returns the buffered points. */
    private fun drainPending(): List<RecordingPointEntity> = synchronized(pendingPoints) {
        val copy = pendingPoints.toList()
        pendingPoints.clear()
        copy
    }

    private fun flushPoints() {
        val batch = drainPending()
        if (batch.isEmpty()) return
        scope.launch {
            runCatching {
                RumbApplication.from(this@RecordingService).database.recordingDao().insertPoints(batch)
            }.onFailure { DebugLog.e("Record", "flush de punts fallit", it) }
        }
    }

    private fun publish() {
        recorder?.let { NativeRecording.publish(it.snapshot(Instant.now())) }
    }

    override fun onDestroy() {
        gps.stop()
        pressure.stop()
        ble?.stop(); ble = null
        // Flush the last buffered points synchronously: an async launch here would be cancelled by
        // scope.cancel() below, silently losing up to the last flush interval on a clean teardown
        // (stopService / task removed) that didn't go through finish().
        val batch = drainPending()
        if (batch.isNotEmpty()) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    RumbApplication.from(this@RecordingService).database.recordingDao().insertPoints(batch)
                }
            }.onFailure { DebugLog.e("Record", "flush final fallit", it) }
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun configFrom(prefs: ViewerPreferences) = RecorderConfig(
        maxAccuracyM = prefs.recMaxAccuracyM,
        minDistanceM = prefs.recMinDistanceM.toDouble(),
        autoLapByPosition = prefs.autoLapByPosition,
        // Distance splits are off during a circuit: the meta owns the laps there.
        autoLapEveryM = if (prefs.circuitActive) 0.0 else prefs.autoLapEveryMFor(prefs.activeSportId).toDouble(),
        presetLapLine = if (prefs.circuitActive) {
            cat.rumb.app.data.opentracks.model.GeoPoint(prefs.circuitLineLat, prefs.circuitLineLng)
        } else {
            null
        },
        autoLapRadiusM = if (prefs.circuitActive) prefs.circuitRadiusM else 25.0,
        autoLapMinLapMs = if (prefs.circuitActive) prefs.circuitMinLapMs else 20_000,
        autoLapMinLapM = if (prefs.circuitActive) prefs.circuitMinLapM else 100.0,
        lapRefDistanceM = if (prefs.circuitActive) prefs.circuitRefDistanceM else 0.0,
    )

    // --- Notification ---

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifiedAt < 3000) return // throttle
        lastNotifiedAt = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(cat.rumb.app.R.string.rec_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val state = recorder?.snapshot(Instant.now())
        val stats = state?.statistics
        val km = (stats?.totalDistanceMeter ?: 0.0) / 1000.0
        val secs = stats?.totalTime?.inWholeSeconds ?: 0
        val text = String.format(
            Locale.US, "%.2f km · %d:%02d:%02d%s",
            km, secs / 3600, (secs % 3600) / 60, secs % 60,
            if (state?.isPaused == true) getString(cat.rumb.app.R.string.rec_notif_on_pause_suffix) else "",
        )
        val paused = state?.isPaused == true

        fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openViewer = PendingIntent.getActivity(
            this, 0,
            Intent().setClassName(this, "cat.rumb.app.viewer.MapViewerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(if (paused) cat.rumb.app.R.string.rec_notif_paused else cat.rumb.app.R.string.rec_notif_recording))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openViewer)
            .addAction(
                0,
                getString(if (paused) cat.rumb.app.R.string.rec_action_resume else cat.rumb.app.R.string.rec_action_pause),
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1),
            )
            .addAction(0, getString(cat.rumb.app.R.string.rec_action_stop), action(ACTION_STOP, 2))
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rumb:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12h safety cap
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    /** Re-reads the auto-pause prefs mid-recording (changed from the viewer quick settings). */
    private fun refreshAutoPauseFromPrefs() {
        if (recorder == null) return
        val prefs = ViewerPreferences.get(this)
        // If we're currently in an auto-created pause, resume first: the new (or null) detector
        // wouldn't own that pause and would strand the recording paused forever.
        if (autoPause?.isAutoPaused == true) {
            doResume(manual = false)
            DebugLog.i("Record", "auto-pausa: reprès per reconfiguració")
        }
        autoPause = if (prefs.recAutoPause) AutoPause(idleAfterSec = prefs.recAutoPauseSec.toLong()) else null
        DebugLog.i("Record", "auto-pausa reconfigurada · on=${prefs.recAutoPause} · ${prefs.recAutoPauseSec}s")
    }

    companion object {
        private const val ACTION_START = "cat.rumb.app.recording.START"
        private const val ACTION_PAUSE = "cat.rumb.app.recording.PAUSE"
        private const val ACTION_RESUME = "cat.rumb.app.recording.RESUME"
        private const val ACTION_STOP = "cat.rumb.app.recording.STOP"
        private const val ACTION_LAP = "cat.rumb.app.recording.LAP"
        private const val ACTION_END_LAPS = "cat.rumb.app.recording.END_LAPS"
        private const val ACTION_REFRESH_AUTOPAUSE = "cat.rumb.app.recording.REFRESH_AUTOPAUSE"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 4243
        private val LAP_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun lap(context: Context) = send(context, ACTION_LAP)
        fun endLaps(context: Context) = send(context, ACTION_END_LAPS)

        /**
         * If a recording finished but the process died before the user saved it, its rows survive in
         * Room (state='FINISHED'). Rebuild the finished snapshot into [NativeRecording] so the viewer
         * can still offer the save dialog. Returns true if one was recovered. No-op when something is
         * already in memory, a live recording exists (that restores via START_STICKY), or nothing is
         * pending. A finished recording with too few points to save is purged.
         */
        suspend fun recoverUnsavedFinished(context: Context): Boolean {
            if (NativeRecording.state.value != null) return false
            val dao = RumbApplication.from(context).database.recordingDao()
            if (dao.activeRecording() != null) return false
            val finished = dao.finishedRecording() ?: return false
            val pts = dao.points(finished.id)
            if (pts.size < 2) {
                dao.deleteFinishedPoints()
                dao.deleteFinishedRecordings()
                return false
            }
            val prefs = ViewerPreferences.get(context)
            val config = RecorderConfig(
                maxAccuracyM = prefs.recMaxAccuracyM,
                minDistanceM = prefs.recMinDistanceM.toDouble(),
            )
            val now = Instant.now()
            val r = TrackRecorder(config)
            r.restore(pts.toSegments(), Instant.ofEpochMilli(finished.startedAt), now)
            // Restore the lap boundaries too, or a recovered finished recording would save with no
            // laps (Laps.fromMarks on an empty list → no ranges → the whole lap structure is lost).
            finished.laps?.let { raw ->
                runCatching { LAP_JSON.decodeFromString<List<LapMark>>(raw) }.getOrNull()?.let { r.restoreLaps(it) }
            }
            r.stop(now)
            NativeRecording.publish(r.snapshot(now))
            DebugLog.w("Record", "gravació finalitzada no desada recuperada · ${pts.size} punts")
            return true
        }

        /** Purges finished recordings from Room once the viewer has saved or discarded them. */
        suspend fun clearFinishedRecordings(context: Context) {
            val dao = RumbApplication.from(context).database.recordingDao()
            dao.deleteFinishedPoints()
            dao.deleteFinishedRecordings()
        }

        /** No-op when no recording is running; otherwise re-reads the auto-pause configuration. */
        fun refreshAutoPause(context: Context) {
            // Don't spin the service up just to reconfigure: only relevant mid-recording.
            if (!NativeRecording.isActive) return
            send(context, ACTION_REFRESH_AUTOPAUSE)
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
