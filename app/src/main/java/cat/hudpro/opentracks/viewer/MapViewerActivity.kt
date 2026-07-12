package cat.hudpro.opentracks.viewer

import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import cat.hudpro.opentracks.ui.theme.HudProTheme
import cat.hudpro.opentracks.viewer.data.DataView
import cat.hudpro.opentracks.viewer.hud.HudOverlay
import cat.hudpro.opentracks.HudProApplication
import cat.hudpro.opentracks.data.endurain.EndurainUploadWorker
import cat.hudpro.opentracks.data.gpx.Gpx
import cat.hudpro.opentracks.data.gpx.GpxPoint
import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.debug.DebugLog
import cat.hudpro.opentracks.data.opentracks.DashboardReader
import cat.hudpro.opentracks.data.opentracks.isDashboardAction
import cat.hudpro.opentracks.data.opentracks.model.Segment
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import cat.hudpro.opentracks.viewer.follow.FollowRouteEngine
import cat.hudpro.opentracks.viewer.hud.HudControls
import cat.hudpro.opentracks.viewer.hud.HudData
import cat.hudpro.opentracks.viewer.hud.HudLayout
import cat.hudpro.opentracks.viewer.hud.HudLayoutStore
import cat.hudpro.opentracks.viewer.hud.LiveMetrics
import cat.hudpro.opentracks.viewer.hud.MetricsCalculator
import kotlinx.coroutines.Job
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
    private var observeJob: Job? = null

    // Optimistic recording state: flipped the instant the user taps start/stop so the HUD reacts
    // immediately, until OpenTracks pushes an authoritative dashboard intent (which clears it).
    private var recordingOverride: Boolean? = null

    private val hudDataFlow = MutableStateFlow(HudData())
    private val controlsFlow = MutableStateFlow(HudControls.disabled)
    private val currentPageFlow = MutableStateFlow(0)
    private val settingsOpenFlow = MutableStateFlow(false)
    private var units = cat.hudpro.opentracks.viewer.hud.Units()
    private var lastWaypoints: List<cat.hudpro.opentracks.data.opentracks.model.Waypoint> = emptyList()
    private var adaptiveZoom = false

    private val locationPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) controller?.enableLocation(this)
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
        DebugLog.i("Viewer", "onCreate · dashboard=${intent.isDashboardAction()} · action=${intent.action}")

        if (intent.isDashboardAction()) {
            reader = runCatching { DashboardReader(intent, contentResolver) }
                .onFailure { Log.e(TAG, "Failed to init DashboardReader", it); DebugLog.e("Viewer", "DashboardReader onCreate fallit", it) }
                .getOrNull()
            DebugLog.i("Viewer", "reader onCreate · isRecording=${reader?.isRecording}")
        }
        applyWindowFlags()

        val prefs = ViewerPreferences.get(this)
        hudLayout = HudLayoutStore.load(prefs)
        units = cat.hudpro.opentracks.viewer.hud.UnitsStore.load(prefs)
        adaptiveZoom = prefs.adaptiveZoom
        setupAnnouncements(prefs)

        // SurfaceView (default): renders GeoJSON overlays reliably. textureMode was a leftover from the
        // old Compose pager and broke the follow-route line rendering.
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)

        // Page 0: map + HUD overlay. Page 1: full data grid. Real views in a ViewPager2 so the
        // MapView stays correctly measured across swipes (a Compose pager corrupts its TextureView).
        val hudView = ComposeView(this).apply {
            setContent {
                HudProTheme {
                    val data by hudDataFlow.collectAsState()
                    val controls by controlsFlow.collectAsState()
                    HudOverlay(data, hudLayout, controls, Modifier.safeDrawingPadding())
                }
            }
        }
        // Dark gradient behind the system status bar so its white icons stay legible over light maps.
        val scrimView = ComposeView(this).apply {
            setContent { HudProTheme { StatusBarScrim() } }
        }
        val mapPage = FrameLayout(this).apply {
            addView(mapView)
            addView(scrimView)
            addView(hudView)
        }
        val dataPage = ComposeView(this).apply {
            setContent {
                HudProTheme {
                    val data by hudDataFlow.collectAsState()
                    DataView(data, Modifier.safeDrawingPadding())
                }
            }
        }
        val pager = ViewPager2(this).apply {
            offscreenPageLimit = 1
            adapter = ViewerPagesAdapter(listOf(mapPage, dataPage))
        }
        val app = HudProApplication.from(this)
        val switcher = ComposeView(this).apply {
            setContent {
                HudProTheme {
                    val page by currentPageFlow.collectAsState()
                    val settingsOpen by settingsOpenFlow.collectAsState()
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp)) {
                        ViewerSwitcher(
                            currentPage = page,
                            onSelect = { pager.setCurrentItem(it, true) },
                            onGear = { settingsOpenFlow.value = true },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                        )
                        if (settingsOpen) {
                            val tracks by app.trackRepository.observeSummaries().collectAsState(initial = emptyList())
                            ViewerQuickSettings(
                                currentBaseMapId = prefs.baseMapId,
                                offlineMaps = cat.hudpro.opentracks.data.map.OfflineMapStore.get(this@MapViewerActivity).list(),
                                currentFollowId = prefs.activeFollowTrackId,
                                tracks = tracks,
                                orientation = prefs.mapOrientation,
                                keepScreenOn = prefs.keepScreenOn,
                                fullscreen = prefs.fullscreen,
                                adaptiveZoom = prefs.adaptiveZoom,
                                onSelectBaseMap = { id ->
                                    prefs.baseMapId = id
                                    controller?.let { c -> applyBaseMap(c, frame = false) { reapplyOverlays(c) } }
                                },
                                onSelectFollow = { id ->
                                    prefs.activeFollowTrackId = id
                                    controller?.let { reloadFollow(it, frame = true) }
                                },
                                onOrientation = { m -> prefs.mapOrientation = m; controller?.let { applyOrientation(it) } },
                                onKeepScreenOn = { b -> prefs.keepScreenOn = b; applyKeepScreenOn(b) },
                                onFullscreen = { b -> prefs.fullscreen = b; applyFullscreen(b) },
                                onAdaptiveZoom = { b -> prefs.adaptiveZoom = b; adaptiveZoom = b },
                                onDismiss = { settingsOpenFlow.value = false },
                            )
                        }
                    }
                }
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { currentPageFlow.value = position }
        })
        setContentView(
            FrameLayout(this).apply {
                addView(pager)
                addView(switcher)
            },
        )

        wasRecording = reader?.isRecording == true
        mapView.getMapAsync { map ->
            val ctrl = MapLibreController(map)
            controller = ctrl
            ctrl.setTrackColorMode(
                cat.hudpro.opentracks.data.map.TrackColorMode.byName(prefs.trackColorMode),
                prefs.trackColor,
            )
            ctrl.setHeadingUp(prefs.mapOrientation == "HEADING_UP")
            setupControls(ctrl)
            val onReady: () -> Unit = {
                // Frame the active route when not recording (no live track to follow yet) so it's visible.
                loadFollowRoute(prefs, ctrl, frame = reader?.isRecording != true)
                reader?.let { observe(it, ctrl) }
                // Show the user's location; request the permission if we don't have it yet.
                if (hasLocationPermission()) {
                    ctrl.enableLocation(this)
                } else {
                    locationPermLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
                Unit
            }
            applyBaseMap(ctrl, frame = true, onReady)
        }

        reader?.start()
    }

    private fun setupControls(ctrl: MapLibreController) {
        ctrl.onUserMoved { followMode = false; emitControls() }
        emitControls()
    }

    /** Applies the base map from prefs (online source or offline MBTiles), then runs [onReady]. */
    private fun applyBaseMap(ctrl: MapLibreController, frame: Boolean, onReady: () -> Unit) {
        val baseMapId = ViewerPreferences.get(this).baseMapId
        val offline = baseMapId
            ?.takeIf { it.startsWith(cat.hudpro.opentracks.data.map.OfflineMap.OFFLINE_PREFIX) }
            ?.let { cat.hudpro.opentracks.data.map.OfflineMapStore.get(this).bySelectionId(it) }
        DebugLog.i("Viewer", "applyBaseMap · id=$baseMapId · offline=${offline?.name} · frame=$frame")
        if (offline != null) {
            ctrl.setOfflineMbtiles(offline.path, offline.attribution) {
                if (frame) offline.bounds?.takeIf { it.size == 4 }?.let { b ->
                    ctrl.frameBounds(b[0], b[1], b[2], b[3])
                }
                onReady()
            }
        } else {
            ctrl.setBaseMap(MapSource.byId(baseMapId), onReady)
        }
    }

    /** After a live style change, re-draw the overlays (a new style discards previous sources/layers). */
    private fun reapplyOverlays(ctrl: MapLibreController) {
        val prefs = ViewerPreferences.get(this)
        ctrl.setTrackColorMode(
            cat.hudpro.opentracks.data.map.TrackColorMode.byName(prefs.trackColorMode),
            prefs.trackColor,
        )
        ctrl.updateTrack(lastSegments, frame = false)
        ctrl.updateWaypoints(lastWaypoints)
        reloadFollow(ctrl)
        if (hasLocationPermission()) ctrl.enableLocation(this)
    }

    /** Loads or clears the followed route according to the current pref (live). */
    private fun reloadFollow(ctrl: MapLibreController, frame: Boolean = false) {
        val prefs = ViewerPreferences.get(this)
        if (prefs.activeFollowTrackId <= 0) {
            ctrl.setFollowRoute(emptyList())
            followEngine = null
            following = false
        } else {
            loadFollowRoute(prefs, ctrl, frame = frame)
        }
    }

    private fun applyOrientation(ctrl: MapLibreController) {
        val heading = ViewerPreferences.get(this).mapOrientation == "HEADING_UP"
        ctrl.setHeadingUp(heading)
        if (!heading) {
            ctrl.northUp()
        } else if (followMode) {
            ctrl.follow(lastSegments, hudDataFlow.value.metrics.bearingDeg)
        }
    }

    /**
     * Target zoom for adaptive zoom: nearer to the next route turn → higher zoom. Returns null when
     * the change from [currentZoom] is too small to bother (hysteresis, avoids constant re-animating).
     */
    private fun adaptiveZoomFor(distToTurnM: Double?, currentZoom: Double): Double? {
        val target = when {
            distToTurnM == null -> 14.0        // no turn ahead (or no route) → wide
            distToTurnM < 60 -> 17.0           // right at the junction
            distToTurnM < 150 -> 16.0
            distToTurnM < 350 -> 15.0
            else -> 14.0                       // long straight
        }
        return if (kotlin.math.abs(target - currentZoom) >= 0.4) target else null
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreen(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        } else {
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun emitControls() {
        val ctrl = controller ?: return
        controlsFlow.value = HudControls(
            followEnabled = followMode,
            onRecenter = {
                followMode = true
                // Prefer the user's GPS location; fall back to the recorded track.
                if (!ctrl.recenterOnLocation(this)) ctrl.follow(lastSegments)
                emitControls()
            },
            onToggleFollow = {
                followMode = !followMode
                if (followMode && !ctrl.recenterOnLocation(this)) ctrl.follow(lastSegments)
                emitControls()
            },
            onNorth = { ctrl.northUp() },
            onZoomIn = { ctrl.zoomIn() },
            onZoomOut = { ctrl.zoomOut() },
            onStartRecording = {
                val ok = cat.hudpro.opentracks.data.opentracks.OpenTracksRecording.start(this)
                DebugLog.i("Record", "start() → $ok · reader=${reader != null}")
                if (ok) {
                    recordingOverride = true
                    refreshRecordingHud()
                    // Standalone viewer: OpenTracks won't push live data here unless HUD Pro is opened
                    // as its dashboard. Tell the user how to see live stats.
                    if (reader == null) {
                        android.widget.Toast.makeText(
                            this,
                            "Gravació iniciada. Per veure les dades en viu, obre HUD Pro des del botó de mapa/tauler d'OpenTracks.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onStopRecording = {
                val ok = cat.hudpro.opentracks.data.opentracks.OpenTracksRecording.stop(this)
                DebugLog.i("Record", "stop() → $ok")
                if (ok) { recordingOverride = false; refreshRecordingHud() }
            },
        )
    }

    /** Re-emit the HUD data so the record button flips immediately after an optimistic start/stop. */
    private fun refreshRecordingHud() {
        val current = hudDataFlow.value
        hudDataFlow.value = current.copy(metrics = current.metrics.copy(isRecording = isRecordingNow()))
    }

    /**
     * OpenTracks re-pushes the dashboard intent (via STATS_TARGET_*) when recording state changes —
     * e.g. right after we start recording, now with isRecording=true. Since this activity is
     * singleTask, that arrives here. Rebuild the reader so the HUD reflects the live recording.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugLog.i(
            "Viewer",
            "onNewIntent · action=${intent.action} · dashboard=${intent.isDashboardAction()} · extras=${intent.extras?.keySet()?.joinToString()}",
        )
        if (!intent.isDashboardAction()) return
        observeJob?.cancel()
        reader?.stop()
        reader = runCatching { DashboardReader(intent, contentResolver) }
            .onFailure { Log.e(TAG, "Failed to init DashboardReader from new intent", it); DebugLog.e("Viewer", "DashboardReader onNewIntent fallit", it) }
            .getOrNull()
        DebugLog.i("Viewer", "reader onNewIntent · isRecording=${reader?.isRecording}")
        // The authoritative push supersedes any optimistic guess. Leave `wasRecording` untouched so
        // observe()'s handleRecordingStopped still detects the recording→stopped transition (Endurain).
        recordingOverride = null
        val ctrl = controller ?: return // map still loading; onCreate's onReady will observe+start
        reader?.let { observe(it, ctrl); it.start() }
        refreshRecordingHud()
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

    private fun loadFollowRoute(prefs: ViewerPreferences, ctrl: MapLibreController, frame: Boolean = false) {
        val id = prefs.activeFollowTrackId
        DebugLog.i("Follow", "loadFollowRoute · id=$id · frame=$frame")
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
                // Always bring the route into view on (re)load so it can't stay off-screen. During
                // recording, the live-follow camera takes over on the next update (followMode intact).
                ctrl.frameFollowRoute()
                DebugLog.i("Follow", "dibuixada · ${geo.size} punts · ${ctrl.followDebug()}")
            } else {
                DebugLog.w("Follow", "ruta buida (id=$id): loadGpxRoute ha tornat 0 punts")
            }
        }
    }

    /** True recording state: the user's optimistic tap wins until OpenTracks pushes a fresh intent. */
    private fun isRecordingNow(): Boolean = recordingOverride ?: (reader?.isRecording == true)

    private fun observe(reader: DashboardReader, ctrl: MapLibreController) {
        observeJob = lifecycleScope.launch {
            combine(reader.segments, reader.waypoints, reader.statistics) { segs, wps, stats ->
                Triple(segs, wps, stats)
            }.collect { (segs, wps, stats) ->
                lastSegments = segs
                lastWaypoints = wps
                ctrl.updateTrack(segs, frame = true)
                ctrl.updateWaypoints(wps)
                val recording = isRecordingNow()
                var metrics = MetricsCalculator.compute(segs, stats, recording)

                // Follow-route: compute state once, drive metrics + progress split + off-route alert.
                val current = segs.lastOrNull()?.lastOrNull { it.latLong != null }?.latLong
                val state = current?.let { followEngine?.update(it) }

                if (followMode && recording) {
                    // Adaptive zoom: closer to a route turn/junction → zoom in; straights → zoom out.
                    val zoom = if (adaptiveZoom) adaptiveZoomFor(state?.distanceToNextTurnM, ctrl.currentZoom) else null
                    ctrl.follow(segs, if (ctrl.headingUp) metrics.bearingDeg else null, zoom)
                }

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

                if (recording) handleAnnouncements(metrics)

                pushSpeed(metrics.speedKmh)
                val routeProfile = followEngine?.elevationProfile
                val nPoints = (followEngine?.points?.size ?: 1)
                hudDataFlow.value = HudData(
                    metrics = metrics,
                    units = units,
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
        val r = reader
        val prefs = ViewerPreferences.get(this)
        // Keep-screen-on / fullscreen honor OUR prefs and OpenTracks' intent extras (either wins).
        if (prefs.keepScreenOn || r?.keepScreenOn == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (r?.showOnLockScreen == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        if (prefs.fullscreen || r?.showFullscreen == true) {
            applyFullscreen(true)
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

/** A subtle dark→transparent gradient covering the status bar, giving its icons contrast on light maps. */
@Composable
private fun StatusBarScrim() {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusBarHeight + 24.dp)
                .background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))),
        )
    }
}
