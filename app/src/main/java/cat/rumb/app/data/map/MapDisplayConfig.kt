package cat.rumb.app.data.map

import cat.rumb.app.data.prefs.ViewerPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-base-map display options. The tiles are pre-rendered raster, so none of this re-fetches
 * anything — it changes how MapLibre/Leaflet PRESENT the same tiles.
 *
 * - [detailReduction]: how many zoom levels to cap below the source's native max. MapLibre then
 *   overzooms (upscales a coarser tile) → fewer, larger features, easier to read at speed.
 * - [grayscale]: raster-saturation −1, so a busy coloured map calms down and your route pops.
 * - [opacity]: fades the map toward the neutral background so the HUD and track dominate.
 *
 * [DEFAULT] is the identity: a source with no saved config looks exactly as it always did.
 */
@Serializable
data class MapDisplayConfig(
    val detailReduction: Int = 0,
    val grayscale: Boolean = false,
    val opacity: Float = 1f,
) {
    /** Effective source maxzoom for a native max, floored so the map never vanishes into one tile. */
    fun effectiveMaxZoom(nativeMax: Int): Int =
        (nativeMax - detailReduction.coerceAtLeast(0)).coerceAtLeast(MIN_DISPLAY_ZOOM)

    val isIdentity: Boolean get() = detailReduction <= 0 && !grayscale && opacity >= 1f

    companion object {
        val DEFAULT = MapDisplayConfig()

        /** Deepest a detail reduction may cap a source: city level, so there's always a usable map. */
        const val MIN_DISPLAY_ZOOM = 11

        /** How many notches the detail slider offers — a source with less headroom offers fewer. */
        fun maxDetailReductionFor(nativeMax: Int): Int = (nativeMax - MIN_DISPLAY_ZOOM).coerceIn(0, 4)
    }
}

object MapDisplayStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The config saved for [sourceId], or [MapDisplayConfig.DEFAULT] when none/unparseable. */
    fun load(prefs: ViewerPreferences, sourceId: String): MapDisplayConfig =
        prefs.mapDisplayJsonFor(sourceId)
            ?.let { runCatching { json.decodeFromString(MapDisplayConfig.serializer(), it) }.getOrNull() }
            ?: MapDisplayConfig.DEFAULT

    /** Persists [config], or clears the key when it's the identity so defaults never carry stale JSON. */
    fun save(prefs: ViewerPreferences, sourceId: String, config: MapDisplayConfig) {
        prefs.setMapDisplayJsonFor(
            sourceId,
            if (config.isIdentity) null else json.encodeToString(MapDisplayConfig.serializer(), config),
        )
    }
}
