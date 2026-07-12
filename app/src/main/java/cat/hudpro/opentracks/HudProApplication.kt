package cat.hudpro.opentracks

import android.app.Application
import android.content.Context
import androidx.room.Room
import cat.hudpro.opentracks.data.endurain.EndurainRepository
import cat.hudpro.opentracks.data.prefs.EndurainPreferences
import cat.hudpro.opentracks.data.tracks.HudProDatabase
import cat.hudpro.opentracks.data.tracks.TrackRepository
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Application + lightweight service locator. Avoids a DI framework to keep the build simple;
 * singletons are created lazily and shared across Activities.
 */
class HudProApplication : Application() {

    val database: HudProDatabase by lazy {
        Room.databaseBuilder(this, HudProDatabase::class.java, "hudpro.db").build()
    }
    val trackRepository: TrackRepository by lazy {
        TrackRepository(database.followTrackDao(), contentResolver)
    }
    val endurainRepository: EndurainRepository by lazy {
        EndurainRepository(EndurainPreferences.get(this))
    }
    val routingRepository: cat.hudpro.opentracks.data.routing.RoutingRepository by lazy {
        cat.hudpro.opentracks.data.routing.RoutingRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        // MapLibre requires a one-time init before any MapView is created.
        MapLibre.getInstance(this)
        // OpenStreetMap (and others) return HTTP 403 "Access blocked" to generic user agents. Identify
        // the app per OSM's tile usage policy so its tiles load. Applies to all MapLibre HTTP requests.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header(
                                "User-Agent",
                                "OpenTracksHUDpro/${BuildConfig.VERSION_NAME} (github.com/borborborja/opentracks-HUDpro)",
                            )
                            .build(),
                    )
                }
                .build(),
        )
    }

    companion object {
        fun from(context: Context): HudProApplication =
            context.applicationContext as HudProApplication
    }
}
