package cat.hudpro.opentracks.viewer

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import cat.hudpro.opentracks.HudProApplication
import cat.hudpro.opentracks.data.endurain.EndurainUploadWorker
import cat.hudpro.opentracks.data.gpx.Gpx
import cat.hudpro.opentracks.data.gpx.GpxPoint
import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.opentracks.DashboardReader
import cat.hudpro.opentracks.data.opentracks.isDashboardAction
import cat.hudpro.opentracks.data.opentracks.model.Segment
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import cat.hudpro.opentracks.viewer.follow.FollowRouteEngine
import cat.hudpro.opentracks.viewer.hud.HudControls
import cat.hudpro.opentracks.viewer.hud.HudData
import cat.hudpro.opentracks.viewer.hud.HudLayout
import cat.hudpro.opentracks.viewer.hud.HudLayoutStore
import cat.hudpro.opentracks.viewer.hud.HudOverlay
import cat.hudpro.opentracks.viewer.hud.LiveMetrics
import cat.hudpro.opentracks.viewer.hud.MetricsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.maplibre.android.maps.MapView

/**
 * The map viewer launched by OpenTracks via its Dashboard API (action `Intent.OpenTracks-Dashboard`),
 * and also usable standalone. Renders the live track over an OSM/ICGC base map with the HUD overlay.
 */
class MapViewerActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private var controller: MapLibreController? = null
    private var reader: DashboardReader? = null

    private val hudDataFlow = MutableStateFlow(HudData())
    private val controlsFlow = MutableStateFlow(HudControls.disabled)
    private lateinit var hudLayout: HudLayout

    private var followEngine: FollowRouteEngine? = null
    private var followMode = true
    private var lastSegments: List<Segment> = emptyList()
    private val speedBuffer = ArrayDeque<Float>()

    private val offRouteAlerter = cat.hudpro.opentracks.viewer.follow.OffRouteAlerter()
    private var following = false
    private var offRouteThreshold = 40
    private var offRouteSound = true
    private var offRouteVibrate = true
    private var offRouteSpoken = true

    // Audio announcements
    private var announcer: cat.hudpro.opentracks.viewer.audio.SpeechAnnouncer? = null
    private var announceScheduler: cat.hudpro.opentracks.viewer.audio.AnnouncementScheduler? = null
    private var announceVoice = false
    private var announceLang = cat.hudpro.opentracks.viewer.audio.AnnounceLang.CA
    private var announceFields = cat.hudpro.opentracks.viewer.audio.AnnounceFields()

    private var wasRecording = false
    private var uploadedThisSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.isDashboardAction()) {
            reader = runCatching { DashboardReader(intent, contentResolver) }
                .onFailure { Log.e(TAG, "Failed to init DashboardReader", it) }
                .getOrNull()
        }
        applyWindowFlags()

        val prefs = ViewerPreferences.get(this)
        hudLayout = HudLayoutStore.load(prefs)
        setupAnnouncements(prefs)

        mapView = MapView(this)
        val hud = ComposeView(this).apply {
            setContent {
                val data by hudDataFlow.collectAsState()
                val controls by controlsFlow.collectAsState()
                Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
                    HudOverlay(
                        data = data,
                        layout = hudLayout,
                        controls = controls,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(mapView)
                addView(hud)
            },
        )
        mapView.onCreate(savedInstanceState)

        val baseMapId = prefs.baseMapId
        wasRecording = reader?.isRecording == true
        mapView.getMapAsync { map ->
            val ctrl = MapLibreController(map)
            controller = ctrl
            ctrl.setTrackColorMode(
                cat.hudpro.opentracks.data.map.TrackColorMode.byName(prefs.trackColorMode),
                prefs.trackColor,
            )
            setupControls(ctrl)
            val onReady: () -> Unit = {
                loadFollowRoute(prefs, ctrl)
                reader?.let { observe(it, ctrl) }
                Unit
            }
            val offline = baseMapId
                ?.takeIf { it.startsWith(cat.hudpro.opentracks.data.map.OfflineMap.OFFLINE_PREFIX) }
                ?.let { cat.hudpro.opentracks.data.map.OfflineMapStore.get(this).bySelectionId(it) }
            if (offline != null) {
                ctrl.setOfflineMbtiles(offline.path, offline.attribution, onReady)
            } else {
                ctrl.setBaseMap(MapSource.byId(baseMapId), onReady)
            }
        }

        reader?.start()
    }

    private fun setupControls(ctrl: MapLibreController) {
        ctrl.onUserMoved { followMode = false; emitControls() }
        emitControls()
    }

    private fun emitControls() {
        val ctrl = controller ?: return
        controlsFlow.value = HudControls(
            followEnabled = followMode,
            onRecenter = { followMode = true; ctrl.follow(lastSegments); emitControls() },
            onToggleFollow = {
                followMode = !followMode
                if (followMode) ctrl.follow(lastSegments)
                emitControls()
            },
            onNorth = { ctrl.northUp() },
            onZoomIn = { ctrl.zoomIn() },
            onZoomOut = { ctrl.zoomOut() },
        )
    }

    private fun setupAnnouncements(prefs: ViewerPreferences) {
        offRouteSpoken = prefs.offRouteSpoken
        if (!prefs.announceEnabled) return
        announceScheduler = cat.hudpro.opentracks.viewer.audio.AnnouncementScheduler(
            cat.hudpro.opentracks.viewer.audio.AnnounceConfig(
                byDistance = prefs.announceByDistance,
                distanceStepKm = prefs.announceDistanceKm.toDouble(),
                byTime = prefs.announceByTime,
                timeStepMin = prefs.announceTimeMin,
            ),
        )
        announceLang = cat.hudpro.opentracks.viewer.audio.AnnounceLang.byCode(prefs.announceLang)
        announceFields = cat.hudpro.opentracks.viewer.audio.AnnounceFields(
            distanceTime = prefs.annDistanceTime,
            pace = prefs.annPace,
            elevation = prefs.annElevation,
            heartRate = prefs.annHeartRate,
            splitPace = prefs.annSplitPace,
        )
        announceVoice = prefs.announceMode == "VOICE"
        if (announceVoice) {
            announcer = cat.hudpro.opentracks.viewer.audio.SpeechAnnouncer(this, announceLang)
        }
    }

    private fun handleAnnouncements(metrics: LiveMetrics) {
        val scheduler = announceScheduler ?: return
        val triggers = scheduler.update(metrics.distanceKm, metrics.movingTime.inWholeSeconds)
        for (t in triggers) {
            if (announceVoice) {
                val snap = cat.hudpro.opentracks.viewer.audio.AnnounceSnapshot(
                    distanceKm = t.distanceKm,
                    elapsedSeconds = t.elapsedSeconds,
                    paceMinPerKm = metrics.paceMinPerKm,
                    speedKmh = metrics.speedKmh,
                    elevationGainM = metrics.elevationGainM,
                    heartRateBpm = metrics.heartRateBpm,
                    splitPaceMinPerKm = t.splitPaceMinPerKm,
                )
                announcer?.speak(cat.hudpro.opentracks.viewer.audio.AnnouncementText.progress(announceLang, announceFields, snap))
            } else {
                AlertFeedback.beeps(1)
            }
        }
    }

    private fun loadFollowRoute(prefs: ViewerPreferences, ctrl: MapLibreController) {
        val id = prefs.activeFollowTrackId
        if (id <= 0) return
        lifecycleScope.launch {
            val gpx = HudProApplication.from(this@MapViewerActivity).trackRepository.loadGpxRoute(id)
            if (gpx.isNotEmpty()) {
                val geo = gpx.map { it.toGeoPoint() }
                followEngine = FollowRouteEngine(geo, gpx.map { it.elevation })
                ctrl.setFollowRoute(geo)
                ctrl.setFollowRouteStyle(prefs.followColor, prefs.followWidth, prefs.followArrows, prefs.followProgress)
                offRouteThreshold = prefs.offRouteThresholdM
                offRouteSound = prefs.offRouteSound
                offRouteVibrate = prefs.offRouteVibrate
                following = true
            }
        }
    }

    private fun observe(reader: DashboardReader, ctrl: MapLibreController) {
        lifecycleScope.launch {
            combine(reader.segments, reader.waypoints, reader.statistics) { segs, wps, stats ->
                Triple(segs, wps, stats)
            }.collect { (segs, wps, stats) ->
                lastSegments = segs
                ctrl.updateTrack(segs, frame = true)
                ctrl.updateWaypoints(wps)
                if (followMode && reader.isRecording) ctrl.follow(segs)

                var metrics = MetricsCalculator.compute(segs, stats, reader.isRecording)

                // Follow-route: compute state once, drive metrics + progress split + off-route alert.
                val current = segs.lastOrNull()?.lastOrNull { it.latLong != null }?.latLong
                val state = current?.let { followEngine?.update(it) }
                if (state != null) {
                    metrics = metrics.copy(
                        remainingDistanceKm = state.remainingKm,
                        offRouteMeters = state.offRouteMeters,
                        bearingToRouteDeg = state.bearingToRouteDeg,
                    )
                    ctrl.updateFollowProgress(state.nearestIndex)
                    if (offRouteAlerter.update(state.offRouteMeters, offRouteThreshold) == cat.hudpro.opentracks.viewer.follow.OffRouteAlerter.Event.ENTERED) {
                        if (offRouteVibrate) AlertFeedback.vibrate(this@MapViewerActivity)
                        val spoken = offRouteSpoken && announceVoice && announcer != null
                        if (spoken) {
                            announcer?.speak(cat.hudpro.opentracks.viewer.audio.AnnouncementText.offRoute(announceLang))
                        } else if (offRouteSound) {
                            AlertFeedback.beep()
                        }
                    }
                }

                if (reader.isRecording) handleAnnouncements(metrics)

                pushSpeed(metrics.speedKmh)
                val routeProfile = followEngine?.elevationProfile
                val nPoints = (followEngine?.points?.size ?: 1)
                hudDataFlow.value = HudData(
                    metrics = metrics,
                    speedSeries = speedBuffer.toList(),
                    // Prefer the followed route's profile; else the recorded track's own altitude.
                    elevationProfile = if (!routeProfile.isNullOrEmpty()) routeProfile else recordedElevation(segs),
                    routeProgress = if (state != null && nPoints > 1) state.nearestIndex.toFloat() / (nPoints - 1) else 1f,
                    following = following,
                    offRouteThresholdM = offRouteThreshold,
                )

                handleRecordingStopped(reader, segs)
            }
        }
    }

    private fun pushSpeed(speedKmh: Double?) {
        if (speedKmh == null) return
        speedBuffer.addLast(speedKmh.toFloat())
        while (speedBuffer.size > SPEED_HISTORY) speedBuffer.removeFirst()
    }

    /** Downsampled altitude series of the recorded track (for the elevation chart when not following). */
    private fun recordedElevation(segments: List<Segment>): List<Float> {
        val alts = segments.flatten().mapNotNull { it.altitude?.toFloat() }
        if (alts.size < 2) return emptyList()
        val step = (alts.size / 120 + 1).coerceAtLeast(1)
        return alts.filterIndexed { i, _ -> i % step == 0 }
    }

    /** When OpenTracks stops recording, auto-enqueue an Endurain upload of the reconstructed GPX. */
    private fun handleRecordingStopped(reader: DashboardReader, segments: List<Segment>) {
        val stoppedNow = wasRecording && !reader.isRecording
        wasRecording = reader.isRecording
        if (!stoppedNow || uploadedThisSession) return
        if (!cat.hudpro.opentracks.data.prefs.EndurainPreferences.get(this).isConfigured) return
        val gpx = buildGpx(segments) ?: return
        val stamp = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(java.time.Instant.now())
        EndurainUploadWorker.enqueue(this, gpx, "opentracks-$stamp.gpx")
        uploadedThisSession = true
    }

    private fun buildGpx(segments: List<Segment>): String? {
        val points = segments.flatten().filter { it.latLong != null && !it.isPause }.map {
            GpxPoint(it.latLong!!.latitude, it.latLong.longitude, elevation = null, time = it.time)
        }
        if (points.isEmpty()) return null
        return Gpx.write("OpenTracks HUD Pro", points)
    }

    private fun applyWindowFlags() {
        val r = reader ?: return
        if (r.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (r.showOnLockScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        if (r.showFullscreen) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    override fun onDestroy() {
        reader?.stop()
        announcer?.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MapViewerActivity"
        const val SPEED_HISTORY = 60
    }
}
