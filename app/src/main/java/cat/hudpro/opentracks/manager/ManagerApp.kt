package cat.hudpro.opentracks.manager

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cat.hudpro.opentracks.manager.screens.EndurainScreen
import cat.hudpro.opentracks.manager.screens.HomeScreen
import cat.hudpro.opentracks.manager.screens.HudDesignerScreen
import cat.hudpro.opentracks.manager.screens.DownloadAreaScreen
import cat.hudpro.opentracks.manager.screens.MapLayersScreen
import cat.hudpro.opentracks.manager.screens.OfflineMapsScreen
import cat.hudpro.opentracks.manager.screens.RouteEditorScreen
import cat.hudpro.opentracks.manager.screens.SettingsScreen
import cat.hudpro.opentracks.manager.screens.TrackLibraryScreen

object Routes {
    const val HOME = "home"
    const val HUD = "hud"
    const val LAYERS = "layers"
    const val OFFLINE = "offline"
    const val TRACKS = "tracks"
    const val ENDURAIN = "endurain"
    const val SETTINGS = "settings"
    const val CREATE_ROUTE = "create_route"
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
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HUD) { HudDesignerScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.LAYERS) { MapLayersScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.OFFLINE) {
            OfflineMapsScreen(
                onBack = { nav.popBackStack() },
                onDownloadArea = { nav.navigate(Routes.DOWNLOAD_AREA) },
            )
        }
        composable(Routes.DOWNLOAD_AREA) { DownloadAreaScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TRACKS) {
            TrackLibraryScreen(
                onBack = { nav.popBackStack() },
                onCreateRoute = { nav.navigate(Routes.CREATE_ROUTE) },
            )
        }
        composable(Routes.CREATE_ROUTE) {
            RouteEditorScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable(Routes.ENDURAIN) { EndurainScreen(onBack = { nav.popBackStack() }) }
    }
}
