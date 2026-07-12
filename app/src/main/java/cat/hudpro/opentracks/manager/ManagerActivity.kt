package cat.hudpro.opentracks.manager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cat.hudpro.opentracks.ui.theme.HudProTheme
import cat.hudpro.opentracks.viewer.MapViewerActivity

/** Entry point of the management app (visual configuration, tracks, offline maps, Endurain). */
class ManagerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "start_route"

        /** Opens the manager directly on [route] (e.g. the HUD/Dades editor from the viewer). */
        fun editIntent(context: android.content.Context, route: String): Intent =
            Intent(context, ManagerActivity::class.java).putExtra(EXTRA_ROUTE, route)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HudProTheme {
                ManagerApp(
                    onOpenViewer = {
                        startActivity(Intent(this, MapViewerActivity::class.java))
                    },
                    startRoute = intent.getStringExtra(EXTRA_ROUTE),
                )
            }
        }
    }
}
