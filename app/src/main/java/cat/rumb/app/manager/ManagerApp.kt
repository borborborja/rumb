package cat.rumb.app.manager

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cat.rumb.app.data.map.BoundingBox
import cat.rumb.app.manager.screens.CompareScreen
import cat.rumb.app.manager.screens.CompetitionDetailScreen
import cat.rumb.app.manager.screens.DataDesignerScreen
import cat.rumb.app.manager.screens.DebugLogScreen
import cat.rumb.app.manager.screens.DesktopModeScreen
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
    const val HUD = "hud"
    const val DATA = "data"
    const val LAYERS = "layers"
    const val OFFLINE_SECTORS = "offline_sectors"
    const val SETTINGS = "settings"
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
    // Launched directly on an editor (pencil in the viewer): back closes the activity, not Home.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val backOrFinish: () -> Unit = { if (!nav.popBackStack()) activity?.finish() }
    NavHost(navController = nav, startDestination = startRoute ?: Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenViewer = onOpenViewer,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenLayers = { nav.navigate(Routes.LAYERS) },
                onOpenRoute = { id -> nav.navigate("${Routes.ROUTE_DETAIL}/$id") },
                onOpenTraining = { id -> nav.navigate("${Routes.TRAINING_DETAIL}/$id") },
                onEditRoute = { id -> nav.navigate("${Routes.EDIT_ROUTE}/$id") },
                onCreateRoute = { nav.navigate(Routes.CREATE_ROUTE) },
                onDownloadRouteMap = { bbox ->
                    nav.navigate("${Routes.DOWNLOAD_AREA}?w=${bbox.west}&s=${bbox.south}&e=${bbox.east}&n=${bbox.north}")
                },
                onOpenCompetition = { nav.navigate("${Routes.COMPETITION_DETAIL}/$it") },
                onStartCompetition = onStartCompetition,
                onOpenRecords = { nav.navigate(Routes.RECORDS) },
                onOpenHeatmap = { nav.navigate(Routes.HEATMAP) },
                onOpenDesktop = { nav.navigate(Routes.DESKTOP) },
                importUri = importUri,
                onImportHandled = onImportHandled,
            )
        }
        composable(Routes.DESKTOP) { DesktopModeScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.RECORDS) {
            RecordsScreen(
                onBack = { nav.popBackStack() },
                onOpenTraining = { id -> nav.navigate("${Routes.TRAINING_DETAIL}/$id") },
            )
        }
        composable(Routes.HEATMAP) {
            HeatmapScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenDebugLog = { nav.navigate(Routes.DEBUG_LOG) },
                onOpenSensors = { nav.navigate(Routes.SENSORS) },
            )
        }
        composable(Routes.SENSORS) { SensorsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DEBUG_LOG) { DebugLogScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HUD) { HudDesignerScreen(onBack = backOrFinish) }
        composable(Routes.DATA) { DataDesignerScreen(onBack = backOrFinish) }
        composable(Routes.LAYERS) {
            MapLayersScreen(
                onBack = { nav.popBackStack() },
                onDownloadArea = { nav.navigate(Routes.DOWNLOAD_AREA) },
                onOpenSectors = { path ->
                    nav.navigate("${Routes.OFFLINE_SECTORS}/${android.net.Uri.encode(path)}")
                },
            )
        }
        composable(
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
        composable(
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
        composable(
            route = "${Routes.COMPARE}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            CompareScreen(trackId = id, onBack = { nav.popBackStack() })
        }
        composable(
            route = "${Routes.COMPETITION_DETAIL}/{refId}",
            arguments = listOf(navArgument("refId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("refId") ?: 0L
            CompetitionDetailScreen(
                refId = id,
                onBack = { nav.popBackStack() },
                onStartCompetition = onStartCompetition,
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
