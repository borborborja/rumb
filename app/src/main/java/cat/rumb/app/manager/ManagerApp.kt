package cat.rumb.app.manager

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cat.rumb.app.BuildConfig
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.premium.PremiumFeature
import cat.rumb.app.manager.screens.PremiumScreen
import cat.rumb.app.manager.screens.PremiumGate
import cat.rumb.app.data.map.BoundingBox
import cat.rumb.app.manager.screens.CompareScreen
import cat.rumb.app.manager.screens.CompetitionDetailScreen
import cat.rumb.app.manager.screens.DataDesignerScreen
import cat.rumb.app.manager.screens.DebugLogScreen
import cat.rumb.app.manager.screens.EndurainDownloadScreen
import cat.rumb.app.manager.screens.HeatmapScreen
import cat.rumb.app.manager.screens.HomeScreen
import cat.rumb.app.manager.screens.HudDesignerScreen
import cat.rumb.app.manager.screens.RecordsScreen
import cat.rumb.app.manager.screens.DownloadAreaScreen
import cat.rumb.app.manager.screens.MapLayersScreen
import cat.rumb.app.manager.screens.OfflineSectorsScreen
import cat.rumb.app.manager.screens.RouteDetailScreen
import cat.rumb.app.manager.screens.SensorsScreen
import cat.rumb.app.manager.screens.RouteEditorScreen
import cat.rumb.app.manager.screens.SettingsScreen
import cat.rumb.app.manager.screens.TrainingDetailScreen

object Routes {
    const val HOME = "home"
    const val PREMIUM = "premium"
    const val HUD = "hud"
    const val DATA = "data"
    const val LAYERS = "layers"
    const val OFFLINE_SECTORS = "offline_sectors"
    const val SETTINGS = "settings"
    const val ENDURAIN_DOWNLOAD = "endurain_download"
    const val DEBUG_LOG = "debug_log"
    const val SENSORS = "sensors"
    const val CREATE_ROUTE = "create_route"
    const val ROUTE_DETAIL = "route_detail"
    const val TRAINING_DETAIL = "training_detail"
    const val COMPETITION_DETAIL = "competition_detail"
    const val COMPARE = "compare"
    const val EDIT_ROUTE = "edit_route"
    const val DOWNLOAD_AREA = "download_area"
    const val RECORDS = "records"
    const val HEATMAP = "heatmap"
    const val DESKTOP = "desktop"
    const val SCALE = "scale"
}

@Composable
fun ManagerApp(
    onOpenViewer: () -> Unit,
    startRoute: String? = null,
    onStartCompetition: (Long) -> Unit = {},
    importUri: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
    navigateTo: String? = null,
    onNavigated: () -> Unit = {},
) {
    val nav = rememberNavController()
    val app = RumbApplication.from(androidx.compose.ui.platform.LocalContext.current)
    val openPremium: () -> Unit = { nav.navigate(Routes.PREMIUM) { launchSingleTop = true } }
    val startCompetition: (Long) -> Unit = { id ->
        if (app.premiumManager.state.value.hasPremium) onStartCompetition(id) else openPremium()
    }
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            cat.rumb.app.data.debug.DebugLog.d("UI", "pantalla → ${entry.destination.route}")
        }
    }
    // Editor route delivered via onNewIntent on the singleTask activity (pencil in the viewer):
    // startDestination is already fixed, so navigate imperatively when a new route arrives.
    androidx.compose.runtime.LaunchedEffect(navigateTo) {
        navigateTo?.let {
            // singleTask: a repeated pencil tap re-enters via onNewIntent. Don't stack a second
            // editor (and its MapView) on top of an identical one.
            if (nav.currentDestination?.route != it) {
                nav.navigate(it) { launchSingleTop = true }
            }
            onNavigated()
        }
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    /**
     * Exit from the HUD/Dades editors, which are ONLY ever opened from the viewer's pencil. Because
     * ManagerActivity is `singleTask`, that pencil either reuses this instance (editor pushed on Home
     * → a plain back would land on Home) or, when no manager exists, creates a fresh one with the
     * editor as its start destination (empty back stack → `finish()` would drop to the launcher —
     * the reported "crash"). Either way the viewer the user was editing is gone, so bring it back
     * explicitly, then drop the editor from this manager.
     */
    val backToViewer: () -> Unit = {
        activity?.let { act ->
            val intent = android.content.Intent(act, cat.rumb.app.viewer.MapViewerActivity::class.java)
            // The pencil forwards the viewer's active competition in the edit intent (and singleTask
            // onNewIntent keeps act.intent current); hand it back so the relaunched viewer re-enters
            // the competition instead of reading "no extra" as leaving it (reference route, ghost and
            // circuit config would all vanish mid-recording).
            val compId = act.intent
                ?.getLongExtra(cat.rumb.app.viewer.MapViewerActivity.EXTRA_COMPETITION_ID, -1L) ?: -1L
            if (compId > 0) intent.putExtra(cat.rumb.app.viewer.MapViewerActivity.EXTRA_COMPETITION_ID, compId)
            act.startActivity(intent)
            if (!nav.popBackStack()) act.finish()
        }
    }
    NavHost(navController = nav, startDestination = startRoute ?: Routes.HOME) {
        composable(Routes.PREMIUM) {
            PremiumScreen(manager = app.premiumManager, onBack = { if (!nav.popBackStack()) activity?.finish() })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenViewer = onOpenViewer,
                onOpenPremium = openPremium,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenLayers = { nav.navigate(Routes.LAYERS) },
                onOpenRoute = { id -> nav.navigate("${Routes.ROUTE_DETAIL}/$id") },
                onOpenTraining = { id -> nav.navigate("${Routes.TRAINING_DETAIL}/$id") },
                onOpenCompare = { id -> nav.navigate("${Routes.COMPARE}/$id") },
                onEditRoute = { id -> nav.navigate("${Routes.EDIT_ROUTE}/$id") },
                onCreateRoute = { nav.navigate(Routes.CREATE_ROUTE) },
                onDownloadRouteMap = { bbox ->
                    nav.navigate("${Routes.DOWNLOAD_AREA}?w=${bbox.west}&s=${bbox.south}&e=${bbox.east}&n=${bbox.north}")
                },
                onOpenCompetition = { nav.navigate("${Routes.COMPETITION_DETAIL}/$it") },
                onStartCompetition = startCompetition,
                onOpenRecords = { nav.navigate(Routes.RECORDS) },
                onOpenHeatmap = { nav.navigate(Routes.HEATMAP) },
                onOpenDesktop = {
                    if (BuildConfig.FLAVOR == "github") nav.navigate(Routes.DESKTOP)
                },
                onOpenScale = { nav.navigate(Routes.SCALE) },
                importUri = importUri,
                onImportHandled = onImportHandled,
            )
        }
        registerDesktopRoute(onBack = { nav.popBackStack() })
        // Weight-control module (self-contained; remove this line to drop the route).
        premiumComposable(Routes.SCALE, PremiumFeature.WEIGHT, { nav.popBackStack() }) { cat.rumb.app.scale.ui.ScaleScreen(onBack = { nav.popBackStack() }) }
        premiumComposable(Routes.RECORDS, PremiumFeature.ADVANCED_ANALYSIS, { nav.popBackStack() }) {
            RecordsScreen(
                onBack = { nav.popBackStack() },
                onOpenTraining = { id -> nav.navigate("${Routes.TRAINING_DETAIL}/$id") },
            )
        }
        premiumComposable(Routes.HEATMAP, PremiumFeature.ADVANCED_ANALYSIS, { nav.popBackStack() }) {
            HeatmapScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenDebugLog = { nav.navigate(Routes.DEBUG_LOG) },
                onOpenPremium = openPremium,
                onOpenSensors = { nav.navigate(Routes.SENSORS) },
                onOpenEndurainDownload = { nav.navigate(Routes.ENDURAIN_DOWNLOAD) },
            )
        }
        premiumComposable(Routes.ENDURAIN_DOWNLOAD, PremiumFeature.CLOUD_SYNC, { nav.popBackStack() }) { EndurainDownloadScreen(onBack = { nav.popBackStack() }) }
        premiumComposable(Routes.SENSORS, PremiumFeature.BLE_SENSORS, { nav.popBackStack() }) { SensorsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DEBUG_LOG) { DebugLogScreen(onBack = { nav.popBackStack() }) }
        premiumComposable(Routes.HUD, PremiumFeature.LAYOUT_EDITING, backToViewer) { HudDesignerScreen(onBack = backToViewer) }
        premiumComposable(Routes.DATA, PremiumFeature.LAYOUT_EDITING, backToViewer) { DataDesignerScreen(onBack = backToViewer) }
        composable(Routes.LAYERS) {
            MapLayersScreen(
                onBack = { nav.popBackStack() },
                onOpenPremium = openPremium,
                onDownloadArea = { nav.navigate(Routes.DOWNLOAD_AREA) },
                onOpenSectors = { path ->
                    nav.navigate("${Routes.OFFLINE_SECTORS}/${android.net.Uri.encode(path)}")
                },
            )
        }
        premiumComposable(
            feature = PremiumFeature.OFFLINE_MAPS,
            onBack = { nav.popBackStack() },
            route = "${Routes.OFFLINE_SECTORS}/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            val path = entry.arguments?.getString("path")?.let { android.net.Uri.decode(it) } ?: ""
            OfflineSectorsScreen(
                mapPath = path,
                onBack = { nav.popBackStack() },
                onDownloadArea = { nav.navigate(Routes.DOWNLOAD_AREA) },
            )
        }
        premiumComposable(
            feature = PremiumFeature.OFFLINE_MAPS,
            onBack = { nav.popBackStack() },
            route = "${Routes.DOWNLOAD_AREA}?w={w}&s={s}&e={e}&n={n}",
            arguments = listOf(
                navArgument("w") { type = NavType.FloatType; defaultValue = Float.NaN },
                navArgument("s") { type = NavType.FloatType; defaultValue = Float.NaN },
                navArgument("e") { type = NavType.FloatType; defaultValue = Float.NaN },
                navArgument("n") { type = NavType.FloatType; defaultValue = Float.NaN },
            ),
        ) { entry ->
            val a = entry.arguments
            val w = a?.getFloat("w") ?: Float.NaN
            val s = a?.getFloat("s") ?: Float.NaN
            val e = a?.getFloat("e") ?: Float.NaN
            val n = a?.getFloat("n") ?: Float.NaN
            val initial = if (!w.isNaN() && !s.isNaN() && !e.isNaN() && !n.isNaN()) {
                BoundingBox(w.toDouble(), s.toDouble(), e.toDouble(), n.toDouble())
            } else {
                null
            }
            DownloadAreaScreen(onBack = { nav.popBackStack() }, initialBbox = initial)
        }
        composable(Routes.CREATE_ROUTE) {
            RouteEditorScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable(
            route = "${Routes.ROUTE_DETAIL}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            RouteDetailScreen(
                trackId = id,
                onBack = { nav.popBackStack() },
                onEditTrace = { nav.navigate("${Routes.EDIT_ROUTE}/$it") },
                onDownloadMap = { bbox ->
                    nav.navigate("${Routes.DOWNLOAD_AREA}?w=${bbox.west}&s=${bbox.south}&e=${bbox.east}&n=${bbox.north}")
                },
                onOpenTraining = { id -> nav.navigate("${Routes.TRAINING_DETAIL}/$id") },
                onOpenPremium = openPremium,
            )
        }
        composable(
            route = "${Routes.TRAINING_DETAIL}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            TrainingDetailScreen(
                trackId = id,
                onBack = { nav.popBackStack() },
                onCompare = { nav.navigate("${Routes.COMPARE}/$it") },
            )
        }
        premiumComposable(
            feature = PremiumFeature.ADVANCED_ANALYSIS,
            onBack = { nav.popBackStack() },
            route = "${Routes.COMPARE}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            CompareScreen(trackId = id, onBack = { nav.popBackStack() })
        }
        premiumComposable(
            feature = PremiumFeature.COMPETITIONS,
            onBack = { nav.popBackStack() },
            route = "${Routes.COMPETITION_DETAIL}/{competitionId}",
            arguments = listOf(navArgument("competitionId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("competitionId") ?: 0L
            CompetitionDetailScreen(
                competitionId = id,
                onBack = { nav.popBackStack() },
                onStartCompetition = startCompetition,
            )
        }
        composable(
            route = "${Routes.EDIT_ROUTE}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            RouteEditorScreen(trackId = id, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
    }
}

/** Destination checks also cover deep links and restored back stacks. */
private fun NavGraphBuilder.premiumComposable(
    route: String,
    feature: PremiumFeature,
    onBack: () -> Unit,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        PremiumGate(feature = feature, onBack = onBack) { content(entry) }
    }
}
