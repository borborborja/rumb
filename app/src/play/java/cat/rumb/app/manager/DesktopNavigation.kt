package cat.rumb.app.manager

import androidx.navigation.NavGraphBuilder

/** Play builds omit the LAN server until desktop transport supports encryption. */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.registerDesktopRoute(onBack: () -> Unit) = Unit
