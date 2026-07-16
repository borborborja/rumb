package cat.rumb.app.data.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Confirms a tile API key by fetching one real tile. A wrong/blocked key returns 401/403; a working
 * one returns 200 with image bytes. Anything else (network error, unexpected code) counts as "not
 * verified", so a key is only ever stored once it has actually served a tile.
 */
object MapKeyVerifier {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** True when [key] fetches a tile for [source]. Keyless sources are trivially valid. */
    suspend fun verify(source: MapSource, key: String): Boolean = withContext(Dispatchers.IO) {
        if (source.apiKeyProvider == null) return@withContext true
        val url = source.url
            .replace("{key}", key.trim())
            .replace("{z}", "1").replace("{x}", "0").replace("{y}", "0")
            .replace("{s}", source.subdomains?.firstOrNull()?.toString() ?: "")
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Rumb (github.com/borborborja/rumb)")
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful && resp.header("Content-Type")?.startsWith("image") == true
            }
        }.getOrDefault(false)
    }
}
