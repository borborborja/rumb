package cat.hudpro.opentracks.data.map

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class OfflineMap(
    val name: String,
    val path: String,
    val attribution: String = "© ICGC / © OpenStreetMap contributors",
) {
    /** Base-map id used in ViewerPreferences to select this offline map. */
    val selectionId: String get() = "$OFFLINE_PREFIX$path"

    companion object {
        const val OFFLINE_PREFIX = "offline:"
    }
}

/**
 * Manages imported MBTiles archives (offline OSM/ICGC tiles). Files are copied into the app's
 * private storage; MapLibre reads them via the `mbtiles://` scheme (see [MapStyleFactory]).
 * Get the official ICGC MBTiles from https://visors.icgc.cat/appdownloads/ (CC-BY © ICGC).
 */
class OfflineMapStore private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "mbtiles").apply { mkdirs() }

    private val serializer = ListSerializer(OfflineMap.serializer())

    fun list(): List<OfflineMap> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            .filter { File(it.path).exists() }
    }

    fun bySelectionId(selectionId: String): OfflineMap? =
        list().firstOrNull { it.selectionId == selectionId }

    /** Copies an MBTiles from a SAF [uri] into private storage and registers it. */
    fun import(resolver: ContentResolver, uri: Uri, name: String): OfflineMap {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "map" }
        val dest = File(dir, if (safe.endsWith(".mbtiles")) safe else "$safe.mbtiles")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            dest.outputStream().use { input.copyTo(it) }
        }
        val map = OfflineMap(name = name.removeSuffix(".mbtiles"), path = dest.absolutePath)
        save(list().filterNot { it.path == map.path } + map)
        return map
    }

    /** Directory where generated/imported MBTiles live. */
    val mbtilesDir: File get() = dir

    /** Registers an already-written MBTiles (e.g. from an area download). */
    fun register(map: OfflineMap) {
        save(list().filterNot { it.path == map.path } + map)
    }

    fun delete(map: OfflineMap) {
        runCatching { File(map.path).delete() }
        save(list().filterNot { it.path == map.path })
    }

    private fun save(maps: List<OfflineMap>) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, maps)).apply()
    }

    companion object {
        private const val KEY = "maps"
        fun get(context: Context) = OfflineMapStore(context.applicationContext)
    }
}
