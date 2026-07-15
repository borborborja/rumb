package cat.rumb.app.viewer

import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.time.Duration.Companion.milliseconds
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
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.debug.DebugLog
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

/**
 * The live map viewer: renders the native recording (and/or the followed route) over an OSM/ICGC
 * base map with the HUD overlay. Driven entirely by the in-process recording engine.
 */
class MapViewerActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private var controller: MapLibreController? = null
    private var observeJob: Job? = null

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
    // Turn-by-turn warnings: (turnIndex, tier) pairs already announced (tier 0 = heads-up, 1 = now).
    private val announcedTurns = mutableSetOf<Pair<Int, Int>>()
    private var turnVoiceOn = true
    private var turnHeadsUp = true
    private var weightKg = 75
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
    private var beepSound = cat.rumb.app.viewer.BeepSound.DOUBLE

    private var recordingWasActive = false

    // Pre-warm the GPS while the viewer is open so tapping record acquires a precise fix almost
    // instantly (the chip is already hot). Stopped on pause and when the recording service takes over.
    private var warmGps: cat.rumb.app.data.recording.GpsSource? = null
    @Volatile private var lastWarmAccuracyM: Float? = null
    @Volatile private var lastWarmLocation: cat.rumb.app.data.opentracks.model.GeoPoint? = null

    // Competition (ghost race) mode: race a previously recorded reference or one of its attempts.
    private var competing = false
    private var competitionRefId = -1L
    private var ghostEngine: cat.rumb.app.data.competition.GhostEngine? = null
    private var ghostCandidates: List<cat.rumb.app.data.tracks.FollowTrackEntity> = emptyList()
    private var opponentId = -1L
    private var ghostHaloOn = true
    private var ghostSecondsOn = true

    // Live "race your laps": ephemeral per-recording state. The ghost is the best previous lap,
    // re-based to each lap's start. No persistence — the saved track stays a normal lapped track.
    private var lapCompeting = false
    private var lapGhost: cat.rumb.app.data.competition.GhostEngine? = null
    private var bestLapMs: Long? = null
    private var lastSeenLapCount = 0
    private var lapCompetePrompted = false
    private val competePromptFlow = MutableStateFlow(false)

    // Circuit mode: record an attempt at a saved circuit. The fixed line is preset (auto-lap arms
    // from the start) and the ghost is the circuit's best lap. Efforts are persisted on save.
    private var circuitMode = false
    private var competitionId = -1L
    // ROUTE competition: the inline reference route to draw once the map controller is ready.
    private var pendingRouteRefPts: List<cat.rumb.app.data.gpx.GpxPoint>? = null
    private var startPillStarted = false

    /** Starts the competition pre-start pill once (idempotent — safe against the map/DB load race). */
    private fun maybeStartStartPill(ctrl: MapLibreController) {
        if (competing && !startPillStarted) {
            startPillStarted = true
            startPointTicker(ctrl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.i("Viewer", "onCreate · action=${intent.action}")
        applyWindowFlags()

        val prefs = ViewerPreferences.get(this)
        hudLayoutFlow.value = HudLayoutStore.load(prefs)
        hudDataFlow.value = hudDataFlow.value.copy(lapManagementEnabled = prefs.lapManagementEnabled)
        units = cat.rumb.app.viewer.hud.UnitsStore.load(prefs)
        adaptiveZoom = prefs.adaptiveZoom
        turnVoiceOn = prefs.turnVoice
        weightKg = prefs.userWeightKg
        setupAnnouncements(prefs)

        // Unified competition mode. Clear any stale circuit prefs first so a normal launch never
        // inherits circuit mode. ROUTE = follow the reference route + whole-track ghost; LAP = preset
        // auto-lap at the fixed line + best-lap ghost. Reference/ghost come from the competition's
        // inline GPX (no live track id).
        prefs.circuitActive = false
        val compId = intent.getLongExtra(EXTRA_COMPETITION_ID, -1L)
        if (compId > 0) {
            competitionId = compId
            ghostHaloOn = prefs.competitionHalo
            ghostSecondsOn = prefs.competitionShowSeconds
            lifecycleScope.launch {
                val app = RumbApplication.from(this@MapViewerActivity)
                val comp = app.competitionRepository.getCompetition(compId) ?: return@launch
                val refPts = runCatching { cat.rumb.app.data.gpx.Gpx.read(comp.referenceGpx.byteInputStream()).points }.getOrDefault(emptyList())
                if (comp.type == cat.rumb.app.data.tracks.CompetitionType.LAP) {
                    circuitMode = true
                    if (cat.rumb.app.data.competition.GhostEngine.isTimed(refPts)) {
                        lapGhost = cat.rumb.app.data.competition.GhostEngine(refPts)
                        lapCompeting = true
                    }
                    prefs.circuitLineLat = comp.lineLat ?: 0.0
                    prefs.circuitLineLng = comp.lineLng ?: 0.0
                    prefs.circuitRadiusM = comp.radiusM ?: 25.0
                    prefs.circuitMinLapMs = comp.minLapMs ?: 20_000
                    prefs.circuitMinLapM = comp.minLapM ?: 100.0
                    prefs.circuitActive = true
                    DebugLog.i("Competi", "mode competició LAP · id=$compId")
                } else {
                    competing = true
                    if (cat.rumb.app.data.competition.GhostEngine.isTimed(refPts)) {
                        ghostEngine = cat.rumb.app.data.competition.GhostEngine(refPts)
                    }
                    pendingRouteRefPts = refPts
                    controller?.let {
                        drawFollowRouteFromPoints(prefs, it, refPts, frame = true)
                        maybeStartStartPill(it) // the map callback may have run before competing was set
                    }
                    DebugLog.i("Competi", "mode competició ROUTE · id=$compId · ${refPts.size} punts")
                }
            }
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
                    val controls by controlsFlow.collectAsState()
                    DataView(
                        data,
                        Modifier.safeDrawingPadding(),
                        reloadKey = reload,
                        onStartRecording = controls.onStartRecording,
                        onStopRecording = controls.onStopRecording,
                        onPauseRecording = controls.onPauseRecording,
                        onResumeRecording = controls.onResumeRecording,
                        onLap = controls.onLap,
                        onEndLaps = controls.onEndLaps,
                        onToggleSetting = { t, b -> onDataToggle(t, b) },
                    )
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
                                lapManagement = prefs.lapManagementEnabled,
                                onLapManagement = { b ->
                                    DebugLog.i("UI", "quick-settings · gestió voltes → $b")
                                    prefs.lapManagementEnabled = b
                                    hudDataFlow.value = hudDataFlow.value.copy(lapManagementEnabled = b)
                                },
                                autoLapByPosition = prefs.autoLapByPosition,
                                onAutoLapByPosition = { b ->
                                    DebugLog.i("UI", "quick-settings · auto-lap posició → $b")
                                    prefs.autoLapByPosition = b
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
                                turnVoice = prefs.turnVoice,
                                onTurnVoice = { b ->
                                    DebugLog.i("UI", "quick-settings · avisos de gir → $b")
                                    prefs.turnVoice = b; turnVoiceOn = b
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
                                    lifecycleScope.launch {
                                        cat.rumb.app.data.recording.RecordingService.clearFinishedRecordings(this@MapViewerActivity)
                                    }
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
                        // "Race your laps?" prompt on the 2nd (or later) lap. Shown over map and Dades.
                        val showCompete by competePromptFlow.collectAsState()
                        if (showCompete) {
                            LapCompetePrompt(
                                onYes = {
                                    lapCompeting = true
                                    competePromptFlow.value = false
                                    DebugLog.i("Record", "carrera de vueltas activada")
                                    refreshRecordingHud()
                                },
                                onNo = { competePromptFlow.value = false },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                            )
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

        mapView.getMapAsync { map ->
            val ctrl = MapLibreController(map)
            controller = ctrl
            ctrl.setTrackColorMode(
                cat.rumb.app.data.map.TrackColorMode.byName(prefs.trackColorMode),
                prefs.trackColor,
            )
            ctrl.setHeadingUp(prefs.mapOrientation == "HEADING_UP")
            ctrl.setTrackingPointStyle(prefs.trackingPointStyle, prefs.trackingPointColor, prefs.trackingPointSize)
            setupControls(ctrl)
            maybeStartStartPill(ctrl)
            startTrackingMarkerTicker(ctrl)
            val onReady: () -> Unit = {
                // Frame the active route when not recording (no live track to follow yet) so it's visible.
                val refPts = pendingRouteRefPts
                if (refPts != null) {
                    drawFollowRouteFromPoints(prefs, ctrl, refPts, frame = !NativeRecording.isActive)
                } else {
                    loadFollowRoute(prefs, ctrl, frame = !NativeRecording.isActive)
                }
                // Reconnect to an ongoing (or just-finished, unsaved) native recording — recovering
                // a finished-but-unsaved one from Room if the process was killed before the save.
                lifecycleScope.launch {
                    val pending = NativeRecording.state.value != null ||
                        cat.rumb.app.data.recording.RecordingService.recoverUnsavedFinished(this@MapViewerActivity)
                    if (pending) observeNative(ctrl)
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

    /** Loads or clears the followed route according to the current state (live). */
    private fun reloadFollow(ctrl: MapLibreController, frame: Boolean = false) {
        val prefs = ViewerPreferences.get(this)
        val refPts = pendingRouteRefPts
        when {
            // ROUTE competition: the reference route is inline (no track id) — redraw it, or a base-map
            // change would wipe it and disable the whole follow/ghost HUD.
            refPts != null -> drawFollowRouteFromPoints(prefs, ctrl, refPts, frame = frame)
            prefs.activeFollowTrackId <= 0 -> {
                ctrl.setFollowRoute(emptyList())
                followEngine = null
                following = false
            }
            else -> loadFollowRoute(prefs, ctrl, frame = frame)
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

    /** Applies a Dades settings-toggle tile: persists the pref and fires the same live effect the
     *  quick-settings sheet uses. Barometer has no live effect (the service reads it at start). */
    private fun onDataToggle(t: cat.rumb.app.viewer.data.DataToggle, b: Boolean) {
        val prefs = ViewerPreferences.get(this)
        when (t) {
            cat.rumb.app.viewer.data.DataToggle.AUTO_PAUSE -> {
                prefs.recAutoPause = b; RecordingService.refreshAutoPause(this)
            }
            cat.rumb.app.viewer.data.DataToggle.TURN_VOICE -> { prefs.turnVoice = b; turnVoiceOn = b }
            cat.rumb.app.viewer.data.DataToggle.ADAPTIVE_ZOOM -> { prefs.adaptiveZoom = b; adaptiveZoom = b }
            cat.rumb.app.viewer.data.DataToggle.KEEP_SCREEN -> { prefs.keepScreenOn = b; applyKeepScreenOn(b) }
            cat.rumb.app.viewer.data.DataToggle.BAROMETER -> { prefs.recBarometer = b }
        }
        DebugLog.i("UI", "data-toggle · ${t.name} → $b")
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
            // The viewer's record button drives the native recording engine.
            onStartRecording = { startNativeRecording(ctrl) },
            onStopRecording = {
                if (NativeRecording.isActive) {
                    DebugLog.i("Record", "native stop demanat")
                    RecordingService.stop(this)
                }
            },
            onPauseRecording = { if (NativeRecording.isActive) RecordingService.pause(this) },
            onResumeRecording = { if (NativeRecording.isActive) RecordingService.resume(this) },
            onLap = { if (NativeRecording.isActive) RecordingService.lap(this) },
            onEndLaps = { if (NativeRecording.isActive) RecordingService.endLaps(this) },
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

    /** Best current GPS accuracy (m): prefers the hot warm-GPS fix, falls back to the map component. */
    private fun bestAccuracyM(ctrl: MapLibreController): Float? {
        val warm = lastWarmAccuracyM
        val comp = ctrl.currentAccuracyM(this)
        return listOfNotNull(warm, comp).minOrNull()
    }

    /**
     * 3-2-1-GO! countdown, gated on GPS precision. Waits for ≤12 m, then relaxes to ≤25 m after
     * [GPS_RELAX_MS]; at [GPS_WAIT_TIMEOUT_MS] it starts anyway with whatever fix there is (never
     * leaves you unable to record) — warning about low accuracy. Tapping the overlay cancels.
     */
    private fun startWithCountdown(ctrl: MapLibreController) {
        lifecycleScope.launch {
            countdownFlow.value = -1
            DebugLog.i("Record", "compte enrere: esperant GPS")
            val started = android.os.SystemClock.elapsedRealtime()
            var lowAccuracy = false
            while (true) {
                if (countdownFlow.value == null) return@launch // cancelled
                val acc = bestAccuracyM(ctrl)
                val waited = android.os.SystemClock.elapsedRealtime() - started
                val gate = if (waited >= GPS_RELAX_MS) MAX_ACCURACY_M else COUNTDOWN_ACCURACY_M
                if (acc != null && acc <= gate) {
                    lowAccuracy = acc > COUNTDOWN_ACCURACY_M
                    DebugLog.i("Record", "compte enrere: GPS ok (${acc}m, gate ${gate}m)")
                    break
                }
                if (waited > GPS_WAIT_TIMEOUT_MS) {
                    lowAccuracy = true
                    DebugLog.w("Record", "compte enrere: timeout, s'inicia igualment (últim: ${acc}m)")
                    break
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
            if (lowAccuracy) {
                android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.rec_low_accuracy_start), android.widget.Toast.LENGTH_LONG).show()
            }
            doStartNativeRecording(ctrl)
            kotlinx.coroutines.delay(700)
            countdownFlow.value = null
        }
    }

    private fun doStartNativeRecording(ctrl: MapLibreController) {
        if (NativeRecording.isActive) return
        stopWarmGps() // the recording service takes over GPS; avoid two concurrent requests
        // Reset per-recording state so a second run in the same viewer session behaves like the first
        // (otherwise progress announcements and turn warnings stay latched from the previous run).
        announceScheduler?.reset()
        announcedTurns.clear()
        offRouteAlerter.reset()
        // Lap racing is per-recording and ephemeral.
        lapCompeting = false
        lapGhost = null
        bestLapMs = null
        lastSeenLapCount = 0
        lapCompetePrompted = false
        competePromptFlow.value = false
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
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
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
    }

    /** Feeds the custom tracking-point marker from the location engine (works recording or idle). */
    private fun startTrackingMarkerTicker(ctrl: MapLibreController) {
        lifecycleScope.launch {
            // Only poll while the viewer is visible (no background work when stopped).
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    val loc = ctrl.lastLocation()
                    if (loc != null) {
                        // Prefer GPS course while moving; else the computed bearing; else keep the last.
                        val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing.toDouble()
                        else hudDataFlow.value.metrics.bearingDeg
                        ctrl.setTrackingMarker(
                            cat.rumb.app.data.opentracks.model.GeoPoint(loc.latitude, loc.longitude),
                            bearing,
                        )
                    } else {
                        // Cold start: the map's engine has no fix yet — fall back to the last known
                        // system/warm position so the marker appears immediately.
                        ctrl.lastKnownGeoPoint(this@MapViewerActivity)?.let {
                            ctrl.setTrackingMarker(it, hudDataFlow.value.metrics.bearingDeg)
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    /** Persists a finished native recording: library entry + GPX + Endurain upload. */
    private fun saveNativeRecording(state: RecorderState, name: String, folder: String, activityType: String?) {
        lifecycleScope.launch {
            val kept = state.points().filter { it.latLong != null && !it.isPause }
            val pts = kept.map {
                GpxPoint(
                    it.latLong!!.latitude, it.latLong.longitude, it.altitude, it.time,
                    heartRate = it.heartRate, cadence = it.cadence, power = it.power,
                )
            }
            if (pts.size >= 2) {
                val newId = RumbApplication.from(this@MapViewerActivity).trackRepository.insertRoute(
                    name, pts, cat.rumb.app.data.tracks.TrackSource.RECORDED, remoteId = null,
                    kind = cat.rumb.app.data.tracks.TrackKind.TRAINING,
                    collection = folder, activityType = activityType,
                    competitionRefId = null,
                    followedRouteId = cat.rumb.app.data.prefs.ViewerPreferences.get(this@MapViewerActivity)
                        .activeFollowTrackId.takeIf { it > 0 },
                )
                // Persist lap ranges (boundary marks → point indices in the saved list).
                val ranges = cat.rumb.app.data.tracks.Laps.fromMarks(state.lapMarks, kept.map { it.id })
                if (ranges.isNotEmpty()) {
                    RumbApplication.from(this@MapViewerActivity).trackRepository
                        .setLaps(newId, cat.rumb.app.data.tracks.Laps.encode(ranges))
                }
                // Competition attempt: file the whole track (ROUTE) or each completed lap (LAP).
                if (competitionId > 0) {
                    RumbApplication.from(this@MapViewerActivity).competitionRepository
                        .addAttemptsFromTrack(competitionId, newId, name, System.currentTimeMillis())
                }
                // Circuit mode is per-recording: clear the preset auto-lap line so a following
                // recording in the same session never inherits a stale finish line.
                cat.rumb.app.data.prefs.ViewerPreferences.get(this@MapViewerActivity).circuitActive = false
                if (folder != "General") {
                    val p = cat.rumb.app.data.prefs.ViewerPreferences.get(this@MapViewerActivity)
                    p.foldersTraining = p.foldersTraining + folder
                }
                cat.rumb.app.data.tracks.TrackMetadataBackfillWorker.enqueue(this@MapViewerActivity)
                // TCX (real laps + calories) when the activity has laps, else GPX.
                val up = cat.rumb.app.data.prefs.ViewerPreferences.get(this@MapViewerActivity)
                val built = cat.rumb.app.data.gpx.ActivityFile.build(
                    cat.rumb.app.data.sync.SyncTargets.safeName(name), pts, ranges, activityType,
                    up.userWeightKg, up.userAge, up.userSex,
                )
                cat.rumb.app.data.sync.SyncTargets.enqueueAll(this@MapViewerActivity, newId, built.fileName, built.content)
                DebugLog.i("Record", "desada «$name» · ${pts.size} punts · ${built.fileName} · sync encuat")
                android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.viewer_toast_activity_saved), android.widget.Toast.LENGTH_SHORT).show()
            }
            // Purge the durable crash-safety rows only now that the track is saved (or too short).
            cat.rumb.app.data.recording.RecordingService.clearFinishedRecordings(this@MapViewerActivity)
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

    private fun setupAnnouncements(prefs: ViewerPreferences) {
        offRouteSpoken = prefs.offRouteSpoken
        beepSound = cat.rumb.app.viewer.BeepSound.byIndex(prefs.announceBeepSound)
        turnHeadsUp = prefs.turnHeadsUp
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
                AlertFeedback.beeps(1, beepSound)
            }
        }
    }

    private fun loadFollowRoute(prefs: ViewerPreferences, ctrl: MapLibreController, frame: Boolean = false) {
        val id = prefs.activeFollowTrackId
        DebugLog.i("Follow", "loadFollowRoute · id=$id · frame=$frame")
        announcedTurns.clear() // new route → new turn indices
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
                // Frame the route only when asked (route selection / initial load). During a
                // base-map change mid-recording the caller passes frame=false so the live-follow
                // camera isn't yanked off the athlete's position.
                if (frame) ctrl.frameFollowRoute()
                DebugLog.i("Follow", "dibuixada · ${geo.size} punts · ${ctrl.followDebug()}")
                maybePrefetchRoute(prefs, ctrl, id)
            } else {
                DebugLog.w("Follow", "ruta buida (id=$id): loadGpxRoute ha tornat 0 punts")
            }
        }
    }

    /** Draws a follow route directly from inline points (ROUTE competition reference; no track id). */
    private fun drawFollowRouteFromPoints(
        prefs: ViewerPreferences,
        ctrl: MapLibreController,
        gpx: List<cat.rumb.app.data.gpx.GpxPoint>,
        frame: Boolean = false,
    ) {
        announcedTurns.clear()
        if (gpx.isEmpty()) return
        val geo = gpx.map { it.toGeoPoint() }
        followEngine = FollowRouteEngine(geo, gpx.map { it.elevation })
        ctrl.setFollowRoute(geo)
        ctrl.setFollowRouteStyle(prefs.followColor, prefs.followWidth, prefs.followArrows, prefs.followProgress)
        offRouteThreshold = prefs.offRouteThresholdM
        offRouteSound = prefs.offRouteSound
        offRouteVibrate = prefs.offRouteVibrate
        following = true
        if (frame) ctrl.frameFollowRoute()
        DebugLog.i("Follow", "ruta inline dibuixada · ${geo.size} punts")
    }

    private var prefetchedRouteKey: String? = null

    /**
     * Auto-downloads the followed route's map tiles (a corridor) into the current online source's
     * offline archive so the whole track works without coverage. Only for an online raster base map,
     * when connected, once per (route, source), and if the user enabled route prefetch.
     */
    private fun maybePrefetchRoute(prefs: ViewerPreferences, ctrl: MapLibreController, trackId: Long) {
        if (!prefs.prefetchOnFollow) return
        val baseMapId = prefs.baseMapId
        if (baseMapId?.startsWith(cat.rumb.app.data.map.OfflineMap.OFFLINE_PREFIX) == true) return // already offline
        val src = MapSource.byId(baseMapId)
        if (src.kind != MapSource.Kind.RASTER || !src.offlineAllowed) return
        val key = "$trackId:${src.id}"
        if (prefetchedRouteKey == key) return
        if (!cat.rumb.app.data.map.Connectivity.isOnline(this)) return
        prefetchedRouteKey = key
        val z = ctrl.currentZoom.toInt()
        val minZ = maxOf(10, z - 1)
        val maxZ = maxOf(minZ, minOf(src.maxZoom, z + 1))
        DebugLog.i("Prefetch", "encuant ruta $trackId · ${src.id} · zoom $minZ-$maxZ")
        cat.rumb.app.data.map.RoutePrefetchWorker.enqueue(this, trackId, src.id, minZ, maxZ)
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

    /** True recording state: driven by the native engine. */
    private fun isRecordingNow(): Boolean = cat.rumb.app.data.recording.NativeRecording.isActive

    /** Native engine source: consumes the in-process recording service. */
    private var lowAccuracyToastShown = false

    private fun observeNative(ctrl: MapLibreController) {
        observeJob?.cancel()
        lowAccuracyToastShown = false
        observeJob = lifecycleScope.launch {
            cat.rumb.app.data.recording.NativeRecording.state.collect { s ->
                if (s == null) return@collect
                // Warned once if the engine had to relax its precision gate to start (poor GPS).
                if (s.startedLowAccuracy && !lowAccuracyToastShown) {
                    lowAccuracyToastShown = true
                    android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.rec_low_accuracy_start), android.widget.Toast.LENGTH_LONG).show()
                }
                processUpdate(s.segments, emptyList(), s.statistics, s.isRecording, ctrl, isPaused = s.isPaused, lapSnapshot = s)
                if (s.isFinished && saveDialogFlow.value == null) {
                    DebugLog.i("Record", "finalitzada · ${s.points().size} punts → diàleg de desar")
                    if (s.points().any { it.latLong != null }) {
                        saveDialogFlow.value = s
                    } else {
                        android.widget.Toast.makeText(this@MapViewerActivity, getString(R.string.viewer_toast_activity_no_points), android.widget.Toast.LENGTH_SHORT).show()
                        cat.rumb.app.data.recording.RecordingService.clearFinishedRecordings(this@MapViewerActivity)
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
        lapSnapshot: cat.rumb.app.data.recording.RecorderState? = null,
    ) {
        // A recording just started: reset per-recording announcement/turn/off-route state. The native
        // path already resets in doStartNativeRecording; this also covers the OpenTracks-companion
        // path (record A, stop, record B in the same viewer session) where B would otherwise inherit
        // A's latched distance milestones and end-of-route turns.
        if (recording && !recordingWasActive) {
            announceScheduler?.reset()
            announcedTurns.clear()
            offRouteAlerter.reset()
        }
        recordingWasActive = recording

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
                        AlertFeedback.beep(beepSound)
                    }
                }
                cat.rumb.app.viewer.follow.OffRouteAlerter.Event.EXITED ->
                    DebugLog.i("Follow", "de nou EN ruta · ${state.offRouteMeters?.toInt()}m")
                cat.rumb.app.viewer.follow.OffRouteAlerter.Event.NONE -> {}
            }

            // Turn-by-turn warnings: heads-up (~150 m) + at-the-turn, each once per turn. Works
            // whether or not we're recording — navigation matters while just following too.
            val onRoute = (state.offRouteMeters ?: 0.0) <= offRouteThreshold
            val turn = state.nextTurn
            if (turnVoiceOn && turn != null && onRoute) {
                val tier = when {
                    turn.distanceM <= 60 -> 1
                    turn.distanceM <= 170 && turnHeadsUp -> 0
                    else -> null
                }
                if (tier != null && announcedTurns.add(turn.index to tier)) {
                    if (announceVoice && announcer != null) {
                        announcer?.speak(
                            cat.rumb.app.viewer.audio.AnnouncementText.turn(
                                announceLang, turn.left, if (tier == 0) 150 else 0,
                            ),
                        )
                    } else {
                        AlertFeedback.beeps(if (tier == 0) 2 else 3, beepSound)
                    }
                    DebugLog.d("Follow", "gir ${if (turn.left) "esquerra" else "dreta"} · tier$tier · ${turn.distanceM.toInt()}m")
                }
            }
            // Forget turns clearly behind us (with a margin) so the set stays small. The margin
            // stops a turn being re-announced when GPS jitter oscillates nearestIndex around its
            // vertex — without it, dipping back below the vertex re-adds the pair and re-fires.
            announcedTurns.removeAll { it.first < state.nearestIndex - 3 }
        }

        // Lap racing: when a new lap starts, keep a ghost of the best previous lap and offer to race.
        lapSnapshot?.let { ls ->
            if (!ls.lapsActive) {
                // Lap block ended (End-Laps): reset so a restarted block (lapCount → 1) is detected
                // again — the counter isn't monotonic across End→Restart.
                lastSeenLapCount = 0
            } else if (ls.lapCount > lastSeenLapCount) {
                lastSeenLapCount = ls.lapCount
                // Slice the just-completed lap (between the last two open marks) and, if it's the
                // fastest so far, make it the ghost to chase. In circuit mode the ghost is the
                // circuit's stored best lap, so we never overwrite it from the current session.
                if (!circuitMode) {
                    val opens = ls.lapMarks.filter {
                        it.type == cat.rumb.app.data.recording.LapMarkType.START ||
                            it.type == cat.rumb.app.data.recording.LapMarkType.SPLIT
                    }
                    val lastLap = ls.lastLapMs
                    if (opens.size >= 2 && lastLap != null && (bestLapMs == null || lastLap < bestLapMs!!)) {
                        val startSeq = opens[opens.size - 2].seq
                        val endSeq = opens[opens.size - 1].seq
                        val slice = buildActiveTimeLapPoints(ls.points(), startSeq, endSeq)
                        if (cat.rumb.app.data.competition.GhostEngine.isTimed(slice)) {
                            bestLapMs = lastLap
                            lapGhost = cat.rumb.app.data.competition.GhostEngine(slice)
                            DebugLog.i("Record", "ghost de vuelta actualizado · millor ${lastLap}ms")
                        }
                    }
                    if (ls.lapCount >= 2 && !lapCompeting && !lapCompetePrompted && lapGhost != null) {
                        competePromptFlow.value = true
                        lapCompetePrompted = true
                    }
                }
            }
        }

        // Ghost race (cross-day competition OR live lap race). Lap racing only runs while a lap block
        // is active; outside it (approach/return) the lap ghost is hidden.
        val lapRacing = lapCompeting && lapSnapshot?.lapsActive == true
        val racing = competing || lapRacing
        if (racing) {
            val ghost = if (lapRacing) lapGhost else ghostEngine
            if (ghost != null) {
                // Lap racing uses the ACTIVE current-lap time as the clock (freezes on pause, matching
                // the lap tiles); cross-day competition keeps its wall-clock-from-start baseline.
                val elapsed = if (lapRacing) {
                    lapSnapshot?.currentLapTimeMs ?: 0L
                } else {
                    stats?.startTime?.takeIf { recording }?.let { java.time.Duration.between(it, java.time.Instant.now()).toMillis() } ?: 0L
                }
                val progress = if (lapRacing) lapSnapshot?.currentLapDistanceM else state?.progressMeters
                val offRoute = !lapRacing && (state?.offRouteMeters ?: 0.0) > offRouteThreshold
                // Off-route (cross-day): the along-track distance is unreliable, so freeze BOTH the
                // dot and the meters together — updating only one makes them visibly disagree.
                if (!offRoute) {
                    ctrl.setGhost(ghost.positionAt(elapsed))
                    if (progress != null) {
                        val delta = progress - ghost.distanceAt(elapsed)
                        // Rough seconds equivalent at the current speed (skip when nearly stopped).
                        val secs = metrics.speedKmh?.takeIf { it > 1.0 }?.let { delta / (it / 3.6) }
                        metrics = metrics.copy(ghostDeltaMeters = delta, ghostSecondsEst = secs)
                    }
                }
            }
        } else if (lapCompeting) {
            // In lap-compete mode but between lap blocks: remove the lap ghost from the map.
            ctrl.setGhost(null)
        }

        // Live calorie estimate (MET-based; generic activity until the user saves with a type).
        metrics = metrics.copy(
            caloriesKcal = cat.rumb.app.data.tracks.Calories.kcal(
                null, weightKg, java.time.Duration.ofMillis(metrics.movingTime.inWholeMilliseconds),
            ).takeIf { recording },
        )

        // Laps (native engine only): current-lap deltas + last lap for the lap tiles.
        lapSnapshot?.let { ls ->
            metrics = metrics.copy(
                lapsActive = ls.lapsActive,
                lapCount = ls.lapCount,
                currentLapDistanceKm = if (ls.lapsActive) ls.currentLapDistanceM / 1000.0 else null,
                currentLapTime = if (ls.lapsActive) ls.currentLapTimeMs.milliseconds else null,
                lastLapTime = ls.lastLapMs?.milliseconds,
            )
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
            lapManagementEnabled = hudDataFlow.value.lapManagementEnabled,
            competing = competing || lapRacing,
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

    private fun applyWindowFlags() {
        val prefs = ViewerPreferences.get(this)
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (prefs.fullscreen) {
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
        // Reflect tracking-point changes made in general Settings while we were away.
        val p = ViewerPreferences.get(this)
        controller?.setTrackingPointStyle(p.trackingPointStyle, p.trackingPointColor, p.trackingPointSize)
        startWarmGps()
    }
    override fun onPause() { stopWarmGps(); mapView.onPause(); super.onPause() }

    /** Warms the GPS chip while the viewer is foregrounded and not yet recording. */
    private fun startWarmGps() {
        // Skip when already warming, when the native engine owns GPS, or without permission —
        // avoids two concurrent GPS consumers.
        if (warmGps != null || NativeRecording.isActive || !hasLocationPermission()) return
        val gps = cat.rumb.app.data.recording.GpsSource(this)
        val ok = gps.start(intervalMs = 1000L) { loc ->
            lastWarmAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null
            lastWarmLocation = cat.rumb.app.data.opentracks.model.GeoPoint(loc.latitude, loc.longitude)
        }
        if (ok) { warmGps = gps; DebugLog.i("Record", "pre-escalfament GPS actiu") } else gps.stop()
    }

    private fun stopWarmGps() {
        warmGps?.stop()
        warmGps = null
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    override fun onDestroy() {
        announcer?.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapViewerActivity"

        /** Long extra: competition id — launches the viewer in competition mode (ROUTE or LAP). */
        const val EXTRA_COMPETITION_ID = "competition_id"

        /** Countdown GPS gate: same threshold as the engine warm-up (RecorderConfig.startAccuracyM). */
        private const val COUNTDOWN_ACCURACY_M = 12f
        /** After this the countdown gate relaxes to [MAX_ACCURACY_M] (matches the engine safety net). */
        private const val GPS_RELAX_MS = 20_000L
        private const val MAX_ACCURACY_M = 25f
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
        initialTypeId = cat.rumb.app.data.tracks.ActivityTypeSuggester.suggest(
            avgMovingSpeedKmh = state.statistics.avgMovingSpeedMeterPerSecond?.times(3.6),
            ascentM = state.statistics.elevationGainMeter,
            distanceM = state.statistics.totalDistanceMeter,
        ),
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

/** Transient "race your laps?" prompt shown on the 2nd+ lap (over map or Dades). */
@Composable
private fun LapCompetePrompt(onYes: () -> Unit, onNo: () -> Unit, modifier: androidx.compose.ui.Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Color(0xF21D3557))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.lap_compete_prompt),
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.TextButton(onClick = onNo) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(R.string.lap_compete_no),
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            androidx.compose.material3.Button(onClick = onYes) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.lap_compete_yes))
            }
        }
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

/**
 * Builds a lap ghost reference re-based to ACTIVE time: a recording pause inside the reference lap
 * advances the wall clock but not the active-lap clock, so the raw timestamps carry a gap the chaser
 * (driven by pause-excluding currentLapTimeMs) never sees. Compress that gap out by excluding any
 * interval touching a pause point, so the ghost's timeline matches the chaser's. Without a pause this
 * reduces to the raw wall-clock offsets — no behavior change.
 */
private fun buildActiveTimeLapPoints(
    points: List<cat.rumb.app.data.opentracks.model.Trackpoint>,
    startSeq: Long,
    endSeq: Long,
): List<GpxPoint> {
    val lap = points.filter { it.id in startSeq until endSeq }
    val out = ArrayList<GpxPoint>(lap.size)
    var activeMs = 0L
    var prev: cat.rumb.app.data.opentracks.model.Trackpoint? = null
    for (p in lap) {
        val before = prev
        if (before != null && !before.isPause && !p.isPause) {
            activeMs += (p.time.toEpochMilli() - before.time.toEpochMilli()).coerceAtLeast(0)
        }
        prev = p
        val ll = p.latLong
        if (ll != null && !p.isPause) {
            out.add(
                GpxPoint(
                    ll.latitude, ll.longitude, p.altitude,
                    java.time.Instant.ofEpochMilli(activeMs),
                    heartRate = p.heartRate, cadence = p.cadence, power = p.power,
                ),
            )
        }
    }
    return out
}
