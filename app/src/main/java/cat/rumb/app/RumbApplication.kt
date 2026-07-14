package cat.rumb.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import cat.rumb.app.data.endurain.EndurainRepository
import cat.rumb.app.data.prefs.EndurainPreferences
import cat.rumb.app.data.tracks.RumbDatabase
import cat.rumb.app.data.tracks.TrackRepository
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Application + lightweight service locator. Avoids a DI framework to keep the build simple;
 * singletons are created lazily and shared across Activities.
 */
class RumbApplication : Application() {

    val database: RumbDatabase by lazy {
        Room.databaseBuilder(this, RumbDatabase::class.java, "rumb.db")
            .addMigrations(RumbDatabase.MIGRATION_1_2, RumbDatabase.MIGRATION_2_3, RumbDatabase.MIGRATION_3_4, RumbDatabase.MIGRATION_4_5, RumbDatabase.MIGRATION_5_6, RumbDatabase.MIGRATION_6_7, RumbDatabase.MIGRATION_7_8)
            .build()
    }
    val trackRepository: TrackRepository by lazy {
        TrackRepository(database.followTrackDao(), contentResolver)
    }
    val endurainRepository: EndurainRepository by lazy {
        EndurainRepository(EndurainPreferences.get(this))
    }
    val routingRepository: cat.rumb.app.data.routing.RoutingRepository by lazy {
        cat.rumb.app.data.routing.RoutingRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        cat.rumb.app.data.debug.DebugLog.install()
        // MapLibre requires a one-time init before any MapView is created.
        MapLibre.getInstance(this)
        cat.rumb.app.data.debug.DebugLog.i("App", "MapLibre inicialitzat")
        // OpenStreetMap (and others) return HTTP 403 "Access blocked" to generic user agents. Identify
        // the app per OSM's tile usage policy so its tiles load. Applies to all MapLibre HTTP requests.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header(
                                "User-Agent",
                                "Rumb/${BuildConfig.VERSION_NAME} (github.com/borborborja/rumb)",
                            )
                            .build(),
                    )
                }
                .build(),
        )
        // Apply the user's online-map (ambient) tile-cache budget so browsing caches within it.
        cat.rumb.app.data.map.MapCache.applyAmbientSize(
            this,
            cat.rumb.app.data.prefs.ViewerPreferences.get(this).mapCacheSizeMb,
        )
        // Backfill ascent/start/municipality for tracks saved before DB v4 (and pending geocodes).
        cat.rumb.app.data.tracks.TrackMetadataBackfillWorker.enqueue(this)
    }

    companion object {
        fun from(context: Context): RumbApplication =
            context.applicationContext as RumbApplication
    }
}
