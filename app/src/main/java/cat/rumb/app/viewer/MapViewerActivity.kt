package cat.rumb.app.viewer

import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
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
import cat.rumb.app.R
import cat.rumb.app.ui.theme.RumbTheme
import cat.rumb.app.viewer.data.DataView
import cat.rumb.app.viewer.hud.HudOverlay
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.endurain.EndurainUploadWorker
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.debug.DebugLog
import cat.rumb.app.data.opentracks.DashboardReader
import cat.rumb.app.data.opentracks.isDashboardAction
import cat.rumb.app.data.opentracks.model.Segment
import cat.rumb.app.data.opentracks.model.TrackStatistics
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.recording.NativeRecording
import cat.rumb.app.data.recording.RecorderState
import cat.rumb.app.data.recording.RecordingService
import cat.rumb.app.viewer.follow.FollowRouteEngine
import cat.rumb.app.viewer.hud.HudControls
import cat.rumb.app.viewer.hud.HudData
import cat.rumb.app.viewer.hud.HudLayout
import cat.rumb.app.viewer.hud.HudLayoutStore
import cat.rumb.app.viewer.hud.LiveMetrics
import cat.rumb.app.viewer.hud.MetricsCalculator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** Finished native recording awaiting the save/discard dialog. */
    private val saveDialogFlow = MutableStateFlow<RecorderState?>(null)

    /** Pre-recording countdown: null hidden, -1 waiting for GPS, 3..1 digits, 0 = GO!. */
    private val countdownFlow = MutableStateFlow<Int?>(null)

    /** Competition pre-start: distance to the start point + whether the fix is precise enough. */
    data class StartPointState(val distanceM: Double, val precise: Boolean)
    private val startPointFlow = MutableStateFlow<StartPointState?>(null)
    private var units = cat.rumb.app.viewer.hud.Units()
    private var lastWaypoints: List<cat.rumb.app.data.opentracks.model.Waypoint> = emptyList()
    private var adaptiveZoom = false

    private val locationPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) controller?.enableLocation(this)
    }

    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val hudLayoutFlow = MutableStateFlow(HudLayout.DEFAULT)
    private val dataReloadFlow = MutableStateFlow(0)

    private var followEngine: FollowRouteEngine? = null
    private var followMode = true
    private var lastSegments: List<Segment> = emptyList()
    private val speedBuffer = ArrayDeque<Float>()
    private val hrBuffer = ArrayDeque<Float>()
    private val cadBuffer = ArrayDeque<Float>()
    private val pwrBuffer = ArrayDeque<Float>()

    private val offRouteAlerter = cat.rumb.app.viewer.follow.OffRouteAlerter()
    private var following = false
    private var offRouteThreshold = 40
    private var offRouteSound = true
    private var offRouteVibrate = true
    private var offRouteSpoken = true

    // Audio announcements
    private var announcer: cat.rumb.app.viewer.audio.SpeechAnnouncer? = null
    private var announceScheduler: cat.rumb.app.viewer.audio.AnnouncementScheduler? = null
    private var announceVoice = false
    private var announceLang = cat.rumb.app.viewer.audio.AnnounceLang.CA
    private var announceFields = cat.rumb.app.viewer.audio.AnnounceFields()

    private var wasRecording = false
    private var uploadedThisSession = false

    // Competition (ghost race) mode: race a previously recorded reference or one of its attempts.
    private var competing = false
    private var competitionRefId = -1L
    private var ghostEngine: cat.rumb.app.data.competition.GhostEngine? = null
    private var ghostCandidates: List<cat.rumb.app.data.tracks.FollowTrackEntity> = emptyList()
    private var opponentId = -1L
    private var ghostHaloOn = true
    private var ghostSecondsOn = true

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
        hudLayoutFlow.value = HudLayoutStore.load(prefs)
        units = cat.rumb.app.viewer.hud.UnitsStore.load(prefs)
        adaptiveZoom = prefs.adaptiveZoom
        setupAnnouncements(prefs)

        // Competition (ghost) mode: the intent carries the reference track; follow it and load a ghost.
        val compRef = intent.getLongExtra(EXTRA_COMPETITION_REF_ID, -1L)
        if (compRef > 0) {
            competing = true
            competitionRefId = compRef
            prefs.activeFollowTrackId = compRef
            ghostHaloOn = prefs.competitionHalo
            ghostSecondsOn = prefs.competitionShowSeconds
            DebugLog.i("Competi", "mode competició · ref=$compRef")
            loadGhostCandidates()
        }

        // SurfaceView (default): renders GeoJSON overlays reliably. textureMode was a leftover from the
        // old Compose pager and broke the follow-route line rendering.
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)

        // Page 0: map + HUD overlay. Page 1: full data grid. Real views in a ViewPager2 so the
        // MapView stays correctly measured across swipes (a Compose pager corrupts its TextureView).
        val hudView = ComposeView(this).apply {
            setContent {
                RumbTheme {
                    val data by hudDataFlow.collectAsState()
                    val controls by controlsFlow.collectAsState()
                    val layout by hudLayoutFlow.collectAsState()
                    HudOverlay(data, layout, controls, Modifier.safeDrawingPadding())
                }
            }
        }
        // Dark gradient behind the system status bar so its white icons stay legible over light maps.
        val scrimView = ComposeView(this).apply {
            setContent { RumbTheme { StatusBarScrim() } }
        }
        // MapGestureFrame keeps horizontal drags on the map (page swipe only from the screen edges).
        val mapPage = MapGestureFrame(this).apply {
            addView(mapView)
            addView(scrimView)
            addView(hudView)
        }
        val dataPage = ComposeView(this).apply {
            setContent {
                RumbTheme {
                    val data by hudDataFlow.collectAsState()
                    val reload by dataReloadFlow.collectAsState()
                    DataView(data, Modifier.safeDrawingPadding(), reloadKey = reload)
                }
            }
        }
        val pager = ViewPager2(this).apply {
            offscreenPageLimit = 1
            adapter = ViewerPagesAdapter(listOf(mapPage, dataPage))
        }
        val app = RumbApplication.from(this)
        val switcher = ComposeView(this).apply {
            setContent {
                RumbTheme {
                    val page by currentPageFlow.collectAsState()
                    val settingsOpen by settingsOpenFlow.collectAsState()
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp)) {
                        // Explicit exit arrow: the system back gesture also collides with map panning.
                        Box(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopStart)
                                .padding(start = 8.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0x99000000))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { finish() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = androidx.compose.ui.res.stringResource(R.string.viewer_cd_exit),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        ViewerSwitcher(
                            currentPage = page,
                            onSelect = { pager.setCurrentItem(it, true) },
                            onGear = { settingsOpenFlow.value = true },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                        )
                        // Pencil at the top-right (mirroring the back arrow): edits the visible page.
                        Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(end = 8.dp)) {
                            EditPageButton {
                                val route = if (page == 0) {
                                    cat.rumb.app.manager.Routes.HUD
                                } else {
                                    cat.rumb.app.manager.Routes.DATA
                                }
                                startActivity(
                                    cat.rumb.app.manager.ManagerActivity.editIntent(this@MapViewerActivity, route),
                                )
                            }
                        }
                        if (settingsOpen) {
                            val tracks by app.trackRepository.observeSummaries().collectAsState(initial = emptyList())
                            ViewerQuickSettings(
                                currentBaseMapId = prefs.baseMapId,
                                offlineMaps = cat.rumb.app.data.map.OfflineMapStore.get(this@MapViewerActivity).list(),
                                currentFollowId = prefs.activeFollowTrackId,
                                tracks = tracks.filter { !it.archived },
                                orientation = prefs.mapOrientation,
                                keepScreenOn = prefs.keepScreenOn,
                                fullscreen = prefs.fullscreen,
                                adaptiveZoom = prefs.adaptiveZoom,
                                onSelectBaseMap = { id ->
                                    DebugLog.i("UI", "quick-settings · mapa base → $id")
                                    prefs.baseMapId = id
                                    controller?.let { c -> applyBaseMap(c, frame = false) { reapplyOverlays(c) } }
                                },
                                onSelectFollow = { id ->
                                    DebugLog.i("UI", "quick-settings · ruta a seguir → id=$id")
                                    prefs.activeFollowTrackId = id
                                    controller?.let { reloadFollow(it, frame = true) }
                                },
                                onOrientation = { m ->
                                    DebugLog.i("UI", "quick-settings · orientació → $m")
                                    prefs.mapOrientation = m; controller?.let { applyOrientation(it) }
                                },
                                onKeepScreenOn = { b ->
                                    DebugLog.i("UI", "quick-settings · pantalla encesa → $b")
                                    prefs.keepScreenOn = b; applyKeepScreenOn(b)
                                },
                                onFullscreen = { b ->
                                    DebugLog.i("UI", "quick-settings · pantalla completa → $b")
                                    prefs.fullscreen = b; applyFullscreen(b)
                                },
                                onAdaptiveZoom = { b ->
                                    DebugLog.i("UI", "quick-settings · zoom adaptatiu → $b")
                                    prefs.adaptiveZoom = b; adaptiveZoom = b
                                },
                                countdown = prefs.recCountdown,
                                onCountdown = { b ->
                                    DebugLog.i("UI", "quick-settings · compte enrere → $b")
                                    prefs.recCountdown = b
                                },
                                autoPause = prefs.recAutoPause,
                                onAutoPause = { b ->
                                    DebugLog.i("UI", "quick-settings · auto-pausa → $b")
                                    prefs.recAutoPause = b
                                    RecordingService.refreshAutoPause(this@MapViewerActivity)
                                },
                                autoPauseSec = prefs.recAutoPauseSec,
                                onAutoPauseSec = { secs ->
                                    DebugLog.i("UI", "quick-settings · auto-pausa llindar → ${secs}s")
                                    prefs.recAutoPauseSec = secs
                                    RecordingService.refreshAutoPause(this@MapViewerActivity)
                                },
                                onDismiss = { settingsOpenFlow.value = false },
                                competing = competing,
                                ghostCandidates = ghostCandidates,
                                opponentId = opponentId,
                                onSelectOpponent = { id ->
                                    DebugLog.i("Competi", "quick-settings · rival → id=$id")
                                    opponentId = id
                                    loadGhost(id)
                                },
                                halo = ghostHaloOn,
                                onHalo = { b ->
                                    DebugLog.i("Competi", "quick-settings · halo → $b")
                                    prefs.competitionHalo = b; ghostHaloOn = b
                                },
                                showSeconds = ghostSecondsOn,
                                onShowSeconds = { b ->
                                    DebugLog.i("Competi", "quick-settings · segons → $b")
                                    prefs.competitionShowSeconds = b; ghostSecondsOn = b
                                },
                            )
                        }
                        val pendingSave by saveDialogFlow.collectAsState()
                        pendingSave?.let { snap ->
                            SaveRecordingDialog(
                                state = snap,
                                onSave = { name, folder, typeId -> saveNativeRecording(snap, name, folder, typeId) },
                                onDiscard = {
                                    DebugLog.i("Record", "descartada")
                                    NativeRecording.clear()
                                    saveDialogFlow.value = null
                                    refreshRecordingHud()
                                },
                            )
                        }
                        // Competition pre-start pill: are we at the reference's start point?
                        val startState by startPointFlow.collectAsState()
                        startState?.let { st ->
                            StartPointPill(st, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                        }
                        // Fullscreen 3-2-1 countdown (renders above everything; tap cancels).
                        val countdown by countdownFlow.collectAsState()
                        countdown?.let { value ->
                            CountdownOverlay(value) {
                                DebugLog.i("Record", "compte enrere cancel·lat")
                                countdownFlow.value = null
                            }
                        }
                    }
                }
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                DebugLog.d("UI", "pàgina → ${if (position == 0) "Mapa" else "Dades"}")
                currentPageFlow.value = position
            }
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
                cat.rumb.app.data.map.TrackColorMode.byName(prefs.trackColorMode),
                prefs.trackColor,
            )
            ctrl.setHeadingUp(prefs.mapOrientation == "HEADING_UP")
            setupControls(ctrl)
            if (competing) startPointTicker(ctrl)
            val onReady: () -> Unit = {
                // Frame the active route when not recording (no live track to follow yet) so it's visible.
                loadFollowRoute(prefs, ctrl, frame = reader?.isRecording != true && !NativeRecording.isActive)
                when {
                    reader != null -> observe(reader!!, ctrl)
                    // Reconnect to an ongoing (or just-finished, unsaved) native recording.
                    NativeRecording.state.value != null -> observeNative(ctrl)
                }
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
            ?.takeIf { it.startsWith(cat.rumb.app.data.map.OfflineMap.OFFLINE_PREFIX) }
            ?.let { cat.rumb.app.data.map.OfflineMapStore.get(this).bySelectionId(it) }
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
            cat.rumb.app.data.map.TrackColorMode.byName(prefs.trackColorMode),
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
        return (if (kotlin.math.abs(target - currentZoom) >= 0.4) target else null)?.also {
            DebugLog.d("UI", "zoom adaptatiu · gir a ${distToTurnM?.toInt() ?: "-"}m → zoom $it (era ${"%.1f".format(currentZoom)})")
        }
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
            // The viewer's record button ALWAYS drives the native engine. OpenTracks is only the data
            // source when Rumb was opened from its dashboard (and then its own UI controls it).
            onStartRecording = { startNativeRecording(ctrl) },
            onStopRecording = {
                if (NativeRecording.isActive) {
                    DebugLog.i("Record", "native stop demanat")
                    RecordingService.stop(this)
                } else if (reader?.isRecording == true || recordingOverride == true) {
                    // Companion session started by OpenTracks: stop it there.
                    val ok = cat.rumb.app.data.opentracks.OpenTracksRecording.stop(this)
                    DebugLog.i("Record", "companion stop() → $ok")
                    if (ok) { recordingOverride = false; refreshRecordingHud() }
                }
            },
            onPauseRecording = { if (NativeRecording.isActive) RecordingService.pause(this) },
            onResumeRecording = { if (NativeRecording.isActive) RecordingService.resume(this) },
        )
    }

    /** Starts the native recording engine (permissions → optional countdown → service). */
    private fun startNativeRecording(ctrl: MapLibreController) {
        if (NativeRecording.isActive) return
        if (countdownFlow.value != null) return // countdown already running (double tap)
        if (!hasLocationPermission()) {
            locationPermLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            android.widget.Toast.makeText(this, getString(R.string.viewer_toast_location_permission_record), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val countdownOn = cat.rumb.app.data.prefs.ViewerPreferences.get(this).recCountdown
        if (countdownOn) startWithCountdown(ctrl) else doStartNativeRecording(ctrl)
    }

    /**
     * 3-2-1-GO! countdown, gated on GPS precision: no digit shows until the fix is at least as
     * precise as the engine's warm-up gate (12 m), so the first fix after GO! isn't rejected.
     * Tapping the overlay cancels (countdownFlow → null, checked at every step).
     */
    private fun startWithCountdown(ctrl: MapLibreController) {
        lifecycleScope.launch {
            countdownFlow.value = -1
            DebugLog.i("Record", "compte enrere: esperant GPS")
            val deadline = android.os.SystemClock.elapsedRealtime() + GPS_WAIT_TIMEOUT_MS
            while (true) {
                if (countdownFlow.value == null) return@launch // cancelled
                val acc = ctrl.currentAccuracyM(this@MapViewerActivity)
                if (acc != null && acc <= COUNTDOWN_ACCURACY_M) {
                    DebugLog.i("Record", "compte enrere: GPS ok (${acc}m)")
                    break
                }
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    countdownFlow.value = null
                    android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.countdown_gps_timeout), android.widget.Toast.LENGTH_LONG).show()
                    DebugLog.w("Record", "compte enrere abandonat: sense fix precís (últim: ${acc}m)")
                    return@launch
                }
                kotlinx.coroutines.delay(500)
            }
            for (n in 3 downTo 1) {
                if (countdownFlow.value == null) return@launch
                countdownFlow.value = n
                AlertFeedback.beep()
                kotlinx.coroutines.delay(1000)
            }
            if (countdownFlow.value == null) return@launch
            countdownFlow.value = 0
            AlertFeedback.beeps(2)
            doStartNativeRecording(ctrl)
            kotlinx.coroutines.delay(700)
            countdownFlow.value = null
        }
    }

    private fun doStartNativeRecording(ctrl: MapLibreController) {
        if (NativeRecording.isActive) return
        requestNotificationPermission()
        DebugLog.i("Record", "native start")
        RecordingService.start(this)
        observeNative(ctrl)
        followMode = true
        refreshRecordingHud()
        emitControls()
    }

    /**
     * Competition pre-start ticker: while not recording, publishes the distance from the current
     * position to the reference track's start point (the pill above the map).
     */
    private fun startPointTicker(ctrl: MapLibreController) {
        lifecycleScope.launch {
            while (true) {
                val ghost = ghostEngine
                startPointFlow.value = if (competing && ghost != null && !NativeRecording.isActive) {
                    val loc = ctrl.lastKnownGeoPoint(this@MapViewerActivity)
                    val acc = ctrl.currentAccuracyM(this@MapViewerActivity)
                    loc?.let {
                        StartPointState(
                            distanceM = cat.rumb.app.viewer.hud.MetricsCalculator.distanceMeters(it, ghost.positionAt(0)),
                            // A fix worse than the recording gate can't honestly place you anywhere.
                            precise = acc != null && acc <= 25f,
                        )
                    }
                } else {
                    null
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    /** Persists a finished native recording: library entry + GPX + Endurain upload. */
    private fun saveNativeRecording(state: RecorderState, name: String, folder: String, activityType: String?) {
        lifecycleScope.launch {
            val pts = state.points()
                .filter { it.latLong != null && !it.isPause }
                .map {
                    GpxPoint(
                        it.latLong!!.latitude, it.latLong.longitude, it.altitude, it.time,
                        heartRate = it.heartRate, cadence = it.cadence, power = it.power,
                    )
                }
            if (pts.size >= 2) {
                RumbApplication.from(this@MapViewerActivity).trackRepository.insertRoute(
                    name, pts, cat.rumb.app.data.tracks.TrackSource.RECORDED, remoteId = null,
                    kind = cat.rumb.app.data.tracks.TrackKind.TRAINING,
                    collection = folder, activityType = activityType,
                    competitionRefId = competitionRefId.takeIf { competing },
                )
                if (folder != "General") {
                    val p = cat.rumb.app.data.prefs.ViewerPreferences.get(this@MapViewerActivity)
                    p.foldersTraining = p.foldersTraining + folder
                }
                cat.rumb.app.data.tracks.TrackMetadataBackfillWorker.enqueue(this@MapViewerActivity)
                val gpx = Gpx.write(name, pts)
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "activitat" }
                EndurainUploadWorker.enqueue(this@MapViewerActivity, gpx, "$safe.gpx")
                DebugLog.i("Record", "desada «$name» · ${pts.size} punts · Endurain encuat")
                android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.viewer_toast_activity_saved), android.widget.Toast.LENGTH_SHORT).show()
            }
            NativeRecording.clear()
            saveDialogFlow.value = null
            refreshRecordingHud()
        }
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
        if (NativeRecording.isActive) {
            // The native engine is recording: don't let an OpenTracks dashboard steal the pipeline.
            DebugLog.w("Viewer", "dashboard ignorat: gravació nativa en curs")
            android.widget.Toast.makeText(this, getString(R.string.viewer_toast_native_recording_active), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
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
        announceScheduler = cat.rumb.app.viewer.audio.AnnouncementScheduler(
            cat.rumb.app.viewer.audio.AnnounceConfig(
                byDistance = prefs.announceByDistance,
                distanceStepKm = prefs.announceDistanceKm.toDouble(),
                byTime = prefs.announceByTime,
                timeStepMin = prefs.announceTimeMin,
            ),
        )
        announceLang = cat.rumb.app.viewer.audio.AnnounceLang.byCode(prefs.announceLang)
        announceFields = cat.rumb.app.viewer.audio.AnnounceFields(
            distanceTime = prefs.annDistanceTime,
            pace = prefs.annPace,
            elevation = prefs.annElevation,
            heartRate = prefs.annHeartRate,
            splitPace = prefs.annSplitPace,
        )
        announceVoice = prefs.announceMode == "VOICE"
        if (announceVoice) {
            announcer = cat.rumb.app.viewer.audio.SpeechAnnouncer(this, announceLang)
        }
    }

    private fun handleAnnouncements(metrics: LiveMetrics) {
        val scheduler = announceScheduler ?: return
        val triggers = scheduler.update(metrics.distanceKm, metrics.movingTime.inWholeSeconds)
        for (t in triggers) {
            if (announceVoice) {
                val snap = cat.rumb.app.viewer.audio.AnnounceSnapshot(
                    distanceKm = t.distanceKm,
                    elapsedSeconds = t.elapsedSeconds,
                    paceMinPerKm = metrics.paceMinPerKm,
                    speedKmh = metrics.speedKmh,
                    elevationGainM = metrics.elevationGainM,
                    heartRateBpm = metrics.heartRateBpm,
                    splitPaceMinPerKm = t.splitPaceMinPerKm,
                )
                announcer?.speak(cat.rumb.app.viewer.audio.AnnouncementText.progress(announceLang, announceFields, snap))
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
            val gpx = RumbApplication.from(this@MapViewerActivity).trackRepository.loadGpxRoute(id)
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

    /**
     * Loads the ghost candidates (the competition reference + every attempt recorded against it)
     * and picks the default opponent: the best (lowest) timed attempt, else the reference itself.
     */
    private fun loadGhostCandidates() {
        lifecycleScope.launch {
            val all = RumbApplication.from(this@MapViewerActivity).trackRepository.observeSummaries().first()
            val ref = all.firstOrNull { it.id == competitionRefId }
            ghostCandidates = listOfNotNull(ref) + all.filter { it.competitionRefId == competitionRefId }
            val best = ghostCandidates.filter { (it.durationMs ?: 0) > 0 }.minByOrNull { it.durationMs!! } ?: ref
            opponentId = best?.id ?: -1L
            DebugLog.i("Competi", "candidats=${ghostCandidates.size} · rival per defecte id=$opponentId")
            if (opponentId > 0) loadGhost(opponentId)
        }
    }

    /** (Re)builds the ghost engine from track [id]'s GPX (fails on untimed tracks → toast). */
    private fun loadGhost(id: Long) {
        lifecycleScope.launch {
            runCatching {
                cat.rumb.app.data.competition.GhostEngine(
                    RumbApplication.from(this@MapViewerActivity).trackRepository.loadGpxRoute(id),
                )
            }.onSuccess {
                ghostEngine = it
                DebugLog.i("Competi", "ghost carregat · id=$id · ${it.totalMeters.toInt()}m · ${it.totalDurationMs / 1000}s")
            }.onFailure { e ->
                ghostEngine = null
                android.widget.Toast.makeText(
                    this@MapViewerActivity,
                    getString(R.string.viewer_toast_ghost_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                DebugLog.w("Competi", "ghost fallit · id=$id · ${e.message}")
            }
        }
    }

    /** True recording state: native engine wins; else the user's optimistic tap; else OpenTracks. */
    private fun isRecordingNow(): Boolean =
        if (cat.rumb.app.data.recording.NativeRecording.isActive) true
        else recordingOverride ?: (reader?.isRecording == true)

    /** OpenTracks companion source: consumes the dashboard reader (when opened from OpenTracks). */
    private fun observe(reader: DashboardReader, ctrl: MapLibreController) {
        observeJob = lifecycleScope.launch {
            combine(reader.segments, reader.waypoints, reader.statistics) { segs, wps, stats ->
                Triple(segs, wps, stats)
            }.collect { (segs, wps, stats) ->
                processUpdate(segs, wps, stats, isRecordingNow(), ctrl)
                handleRecordingStopped(reader, segs)
            }
        }
    }

    /** Native engine source: consumes the in-process recording service. */
    private fun observeNative(ctrl: MapLibreController) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            cat.rumb.app.data.recording.NativeRecording.state.collect { s ->
                if (s == null) return@collect
                processUpdate(s.segments, emptyList(), s.statistics, s.isRecording, ctrl, isPaused = s.isPaused)
                if (s.isFinished && saveDialogFlow.value == null) {
                    DebugLog.i("Record", "finalitzada · ${s.points().size} punts → diàleg de desar")
                    if (s.points().any { it.latLong != null }) {
                        saveDialogFlow.value = s
                    } else {
                        android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.viewer_toast_activity_no_points), android.widget.Toast.LENGTH_SHORT).show()
                        cat.rumb.app.data.recording.NativeRecording.clear()
                        refreshRecordingHud()
                    }
                }
            }
        }
    }

    /** Shared pipeline: track/waypoints on the map, metrics, follow-route, announcements, HUD. */
    private fun processUpdate(
        segs: List<Segment>,
        wps: List<cat.rumb.app.data.opentracks.model.Waypoint>,
        stats: TrackStatistics?,
        recording: Boolean,
        ctrl: MapLibreController,
        isPaused: Boolean = false,
    ) {
        lastSegments = segs
        lastWaypoints = wps
        ctrl.updateTrack(segs, frame = true)
        ctrl.updateWaypoints(wps)
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
            when (offRouteAlerter.update(state.offRouteMeters, offRouteThreshold)) {
                cat.rumb.app.viewer.follow.OffRouteAlerter.Event.ENTERED -> {
                    DebugLog.i("Follow", "FORA de ruta · ${state.offRouteMeters?.toInt()}m (llindar ${offRouteThreshold}m)")
                    if (offRouteVibrate) AlertFeedback.vibrate(this@MapViewerActivity)
                    val spoken = offRouteSpoken && announceVoice && announcer != null
                    if (spoken) {
                        announcer?.speak(cat.rumb.app.viewer.audio.AnnouncementText.offRoute(announceLang))
                    } else if (offRouteSound) {
                        AlertFeedback.beep()
                    }
                }
                cat.rumb.app.viewer.follow.OffRouteAlerter.Event.EXITED ->
                    DebugLog.i("Follow", "de nou EN ruta · ${state.offRouteMeters?.toInt()}m")
                cat.rumb.app.viewer.follow.OffRouteAlerter.Event.NONE -> {}
            }
        }

        // Ghost race: place the ghost at its own elapsed-time position and compute the delta vs us.
        if (competing) {
            val ghost = ghostEngine
            if (ghost != null) {
                val startTime = stats?.startTime?.takeIf { recording }
                val elapsed = startTime?.let { java.time.Duration.between(it, java.time.Instant.now()).toMillis() } ?: 0L
                ctrl.setGhost(ghost.positionAt(elapsed))
                val offRoute = (state?.offRouteMeters ?: 0.0) > offRouteThreshold
                if (startTime != null && state != null && !offRoute) {
                    val delta = state.progressMeters - ghost.distanceAt(elapsed)
                    // Rough seconds equivalent at the current speed (skip when nearly stopped).
                    val secs = metrics.speedKmh?.takeIf { it > 1.0 }?.let { delta / (it / 3.6) }
                    metrics = metrics.copy(ghostDeltaMeters = delta, ghostSecondsEst = secs)
                }
            }
        }

        if (recording) handleAnnouncements(metrics)

        pushSpeed(metrics.speedKmh)
        pushSeries(hrBuffer, metrics.heartRateBpm)
        pushSeries(cadBuffer, metrics.cadenceRpm)
        pushSeries(pwrBuffer, metrics.powerW)
        val routeProfile = followEngine?.elevationProfile
        val nPoints = (followEngine?.points?.size ?: 1)
        hudDataFlow.value = HudData(
            metrics = metrics,
            units = units,
            speedSeries = speedBuffer.toList(),
            heartRateSeries = hrBuffer.toList(),
            cadenceSeries = cadBuffer.toList(),
            powerSeries = pwrBuffer.toList(),
            // Prefer the followed route's profile; else the recorded track's own altitude.
            elevationProfile = if (!routeProfile.isNullOrEmpty()) routeProfile else recordedElevation(segs),
            routeProgress = if (state != null && nPoints > 1) state.nearestIndex.toFloat() / (nPoints - 1) else 1f,
            following = following,
            offRouteThresholdM = offRouteThreshold,
            isPaused = isPaused,
            competing = competing,
            ghostHalo = ghostHaloOn,
            ghostShowSeconds = ghostSecondsOn,
        )
    }

    private fun pushSpeed(speedKmh: Double?) {
        if (speedKmh == null) return
        speedBuffer.addLast(speedKmh.toFloat())
        while (speedBuffer.size > SPEED_HISTORY) speedBuffer.removeFirst()
    }

    private fun pushSeries(buffer: ArrayDeque<Float>, value: Double?) {
        if (value == null) return
        buffer.addLast(value.toFloat())
        while (buffer.size > SPEED_HISTORY) buffer.removeFirst()
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
        if (!cat.rumb.app.data.prefs.EndurainPreferences.get(this).isConfigured) return
        val gpx = buildGpx(segments) ?: return
        val stamp = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(java.time.Instant.now())
        EndurainUploadWorker.enqueue(this, gpx, "rumb-$stamp.gpx")
        uploadedThisSession = true
    }

    private fun buildGpx(segments: List<Segment>): String? {
        val points = segments.flatten().filter { it.latLong != null && !it.isPause }.map {
            GpxPoint(it.latLong!!.latitude, it.latLong.longitude, elevation = null, time = it.time)
        }
        if (points.isEmpty()) return null
        return Gpx.write("Rumb", points)
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
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Pick up layout changes made in the editors (pencil button) while we were paused.
        hudLayoutFlow.value = HudLayoutStore.load(ViewerPreferences.get(this))
        dataReloadFlow.value++
    }
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

    companion object {
        private const val TAG = "MapViewerActivity"

        /** Long extra: competition reference track id — launches the viewer in ghost-race mode. */
        const val EXTRA_COMPETITION_REF_ID = "competition_ref_id"

        /** Countdown GPS gate: same threshold as the engine warm-up (RecorderConfig.startAccuracyM). */
        private const val COUNTDOWN_ACCURACY_M = 12f
        private const val GPS_WAIT_TIMEOUT_MS = 30_000L

        /** Within this distance of the reference start, the competition pre-start pill turns green. */
        private const val START_POINT_NEAR_M = 30.0
        private const val SPEED_HISTORY = 60
    }
}

/** Round dark pencil button, outside the switcher pill, that opens the current page's editor. */
@Composable
private fun EditPageButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = androidx.compose.ui.res.stringResource(R.string.viewer_cd_edit_page),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Save/discard dialog for a finished native recording: name + folder + activity type. */
@Composable
private fun SaveRecordingDialog(
    state: RecorderState,
    onSave: (name: String, folder: String, typeId: String?) -> Unit,
    onDiscard: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { cat.rumb.app.data.prefs.ViewerPreferences.get(context) }
    val defaultName = androidx.compose.ui.res.stringResource(
        R.string.viewer_default_activity_name,
        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(state.statistics.startTime ?: java.time.Instant.now()),
    )
    val km = state.statistics.totalDistanceMeter / 1000.0
    val secs = state.statistics.totalTime.inWholeSeconds
    // Folders come from the DB (this runs outside the manager Activity) merged with prefs.
    val folders by androidx.compose.runtime.produceState(initialValue = prefs.foldersTraining.toList().sorted()) {
        val fromDb = RumbApplication.from(context).trackRepository
            .collections(cat.rumb.app.data.tracks.TrackKind.TRAINING)
        value = (prefs.foldersTraining + fromDb).filter { it != "General" }.distinct().sorted()
    }
    cat.rumb.app.manager.screens.TrackSaveDialog(
        title = androidx.compose.ui.res.stringResource(R.string.viewer_save_dialog_title),
        statsLine = String.format(java.util.Locale.US, "%.2f km · %d:%02d:%02d", km, secs / 3600, (secs % 3600) / 60, secs % 60),
        defaultName = defaultName,
        folders = folders,
        activityTypes = cat.rumb.app.manager.screens.rememberActivityTypeOptions(prefs),
        confirmLabel = androidx.compose.ui.res.stringResource(R.string.viewer_save),
        dismissLabel = androidx.compose.ui.res.stringResource(R.string.viewer_discard),
        onConfirm = onSave,
        onDismiss = onDiscard,
        forceChoice = true,
    )
}

/** Competition pre-start pill: green at the start point (≤30 m), amber with the distance otherwise. */
@androidx.compose.runtime.Composable
private fun StartPointPill(state: MapViewerActivity.StartPointState, modifier: androidx.compose.ui.Modifier) {
    val near = state.precise && state.distanceM <= 30.0
    val bg = when {
        !state.precise -> androidx.compose.ui.graphics.Color(0xF2555F6B)
        near -> androidx.compose.ui.graphics.Color(0xF22ECC71)
        else -> androidx.compose.ui.graphics.Color(0xF2F4A261)
    }
    val text = when {
        !state.precise -> androidx.compose.ui.res.stringResource(R.string.countdown_waiting_gps)
        near -> "✓ " + androidx.compose.ui.res.stringResource(R.string.comp_at_start)
        state.distanceM > 999 -> androidx.compose.ui.res.stringResource(R.string.comp_start_distance_km, state.distanceM / 1000.0)
        else -> androidx.compose.ui.res.stringResource(R.string.comp_start_distance, state.distanceM.toInt())
    }
    androidx.compose.material3.Text(
        text,
        color = androidx.compose.ui.graphics.Color.White,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
