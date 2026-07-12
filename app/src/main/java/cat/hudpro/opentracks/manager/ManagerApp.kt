package cat.hudpro.opentracks.manager

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cat.hudpro.opentracks.data.map.BoundingBox
import cat.hudpro.opentracks.manager.screens.EndurainScreen
import cat.hudpro.opentracks.manager.screens.DataDesignerScreen
import cat.hudpro.opentracks.manager.screens.DebugLogScreen
import cat.hudpro.opentracks.manager.screens.HomeScreen
import cat.hudpro.opentracks.manager.screens.HudDesignerScreen
import cat.hudpro.opentracks.manager.screens.DownloadAreaScreen
import cat.hudpro.opentracks.manager.screens.MapLayersScreen
import cat.hudpro.opentracks.manager.screens.OfflineSectorsScreen
import cat.hudpro.opentracks.manager.screens.RouteDetailScreen
import cat.hudpro.opentracks.manager.screens.RouteEditorScreen
import cat.hudpro.opentracks.manager.screens.SettingsScreen
import cat.hudpro.opentracks.manager.screens.TrackLibraryScreen

object Routes {
    const val HOME = "home"
    const val HUD = "hud"
    const val DATA = "data"
    const val LAYERS = "layers"
    const val OFFLINE_SECTORS = "offline_sectors"
    const val TRACKS = "tracks"
    const val ENDURAIN = "endurain"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val CREATE_ROUTE = "create_route"
    const val ROUTE_DETAIL = "route_detail"
    const val EDIT_ROUTE = "edit_route"
    const val DOWNLOAD_AREA = "download_area"
}

@Composable
fun ManagerApp(onOpenViewer: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenViewer = onOpenViewer,
                onNavigate = { nav.navigate(it) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenDebugLog = { nav.navigate(Routes.DEBUG_LOG) },
            )
        }
        composable(Routes.DEBUG_LOG) { DebugLogScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HUD) { HudDesignerScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DATA) { DataDesignerScreen(onBack = { nav.popBackStack() }) }
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
        composable(Routes.TRACKS) {
            TrackLibraryScreen(
                onBack = { nav.popBackStack() },
                onCreateRoute = { nav.navigate(Routes.CREATE_ROUTE) },
                onOpenRoute = { id -> nav.navigate("${Routes.ROUTE_DETAIL}/$id") },
                onDownloadRouteMap = { bbox ->
                    nav.navigate("${Routes.DOWNLOAD_AREA}?w=${bbox.west}&s=${bbox.south}&e=${bbox.east}&n=${bbox.north}")
                },
            )
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
            )
        }
        composable(
            route = "${Routes.EDIT_ROUTE}/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("trackId") ?: 0L
            RouteEditorScreen(trackId = id, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable(Routes.ENDURAIN) { EndurainScreen(onBack = { nav.popBackStack() }) }
    }
}
