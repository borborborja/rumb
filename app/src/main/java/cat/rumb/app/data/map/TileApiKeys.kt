package cat.rumb.app.data.map

import cat.rumb.app.data.prefs.ViewerPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of user-entered tile API keys, keyed by provider (e.g. "tracestrack"). Loaded
 * from prefs at startup and refreshed whenever the user saves a key in Map layers, so the pure URL
 * builders ([MapStyleFactory] and [TileDownloader]) can resolve a source's `{key}` placeholder
 * without threading a Context through every render/download call site.
 */
object TileApiKeys {

    private val keys = ConcurrentHashMap<String, String>()

    fun get(provider: String?): String? = provider?.let { keys[it] }

    fun set(provider: String, key: String?) {
        if (key.isNullOrBlank()) keys.remove(provider) else keys[provider] = key
    }

    /** (Re)loads every keyed provider's stored key from prefs. Call once at startup. */
    fun load(prefs: ViewerPreferences) {
        MapSource.entries.mapNotNull { it.apiKeyProvider }.distinct().forEach { provider ->
            set(provider, prefs.mapApiKeyFor(provider))
        }
    }

    /**
     * [source]'s tile template with `{key}` resolved to the stored key. Keyless sources are returned
     * unchanged; a keyed source with no stored key yields an empty key (its tiles won't load — such
     * sources are hidden from the pickers until a key is verified).
     */
    fun applyKey(source: MapSource): String =
        if (source.apiKeyProvider == null) source.url
        else source.url.replace("{key}", get(source.apiKeyProvider).orEmpty())
}
