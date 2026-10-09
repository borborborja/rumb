package cat.rumb.app.manager

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import cat.rumb.app.manager.screens.DesktopModeScreen

/** The direct APK distribution retains the existing LAN desktop screen. */
internal fun NavGraphBuilder.registerDesktopRoute(onBack: () -> Unit) {
    composable(Routes.DESKTOP) { DesktopModeScreen(onBack = onBack) }
}
