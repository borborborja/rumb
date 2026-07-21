package cat.rumb.app.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import cat.rumb.app.ui.theme.RumbTheme
import cat.rumb.app.viewer.MapViewerActivity

/** Entry point of the management app (visual configuration, tracks, offline maps, Endurain). */
class ManagerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "start_route"

        /**
         * Opens the manager directly on [route] (e.g. the HUD/Dades editor from the viewer). A
         * positive [competitionId] rides along so the editor's back-to-viewer relaunch re-enters
         * the same competition — without it, the relaunched viewer's onCreate reads "no
         * competition" and silently leaves it (empty map: no reference route, no ghost).
         */
        fun editIntent(context: android.content.Context, route: String, competitionId: Long = -1L): Intent =
            Intent(context, ManagerActivity::class.java).putExtra(EXTRA_ROUTE, route).apply {
                if (competitionId > 0) putExtra(MapViewerActivity.EXTRA_COMPETITION_ID, competitionId)
            }
    }

    // A track file opened/shared into Rumb (VIEW/SEND). Reactive so onNewIntent re-triggers import.
    private val importUri = mutableStateOf<Uri?>(null)

    // Editor route requested by the pencil in the viewer. Because ManagerActivity is singleTask,
    // that intent is delivered to onNewIntent on the already-composed NavHost, so startDestination
    // can't take us there — we navigate reactively instead.
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importUri.value = incomingTrackUri(intent)
        enableEdgeToEdge()
        setContent {
            RumbTheme {
                val uri by importUri
                val navTo by pendingRoute
                ManagerApp(
                    onOpenViewer = {
                        startActivity(Intent(this, MapViewerActivity::class.java))
                    },
                    startRoute = intent.getStringExtra(EXTRA_ROUTE),
                    onStartCompetition = { competitionId ->
                        startActivity(
                            Intent(this, MapViewerActivity::class.java)
                                .putExtra(MapViewerActivity.EXTRA_COMPETITION_ID, competitionId),
                        )
                    },
                    importUri = uri,
                    onImportHandled = { importUri.value = null },
                    navigateTo = navTo,
                    onNavigated = { pendingRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingTrackUri(intent)?.let { importUri.value = it }
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    /** Pulls a track Uri from an ACTION_VIEW (data) or ACTION_SEND (EXTRA_STREAM) intent. */
    private fun incomingTrackUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> null
    }
}
