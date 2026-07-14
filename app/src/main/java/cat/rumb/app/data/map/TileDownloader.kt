package cat.rumb.app.data.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Seeds an MBTiles archive by downloading raster tiles for a bbox over a zoom range from a
 * [MapSource] template. Sequential + throttled to respect tile-server policies (OSM discourages bulk
 * downloads; ICGC CC-BY allows caching). Skips already-present tiles so a run is resumable.
 */
class TileDownloader(
    private val client: OkHttpClient = OkHttpClient(),
    private val throttleMs: Long = 40,
) {
    data class Progress(val done: Long, val total: Long, val failed: Long)

    suspend fun download(
        source: MapSource,
        bbox: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        outFile: File,
        onProgress: suspend (Progress) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        require(source.kind == MapSource.Kind.RASTER) { "Només capes raster" }
        val total = TileMath.tileCount(bbox, minZoom, maxZoom)
        var done = 0L
        var failed = 0L

        MbtilesWriter(outFile).use { writer ->
            writer.writeMetadata(source.displayName, bbox, minZoom, maxZoom, source.attribution)
            for (z in minZoom..maxZoom) {
                val range = TileMath.tileRangeForBbox(bbox, z)
                var x = range.xMin
                while (x <= range.xMax) {
                    var y = range.yMin
                    while (y <= range.yMax) {
                        currentCoroutineContext().ensureActive()
                        if (!writer.hasTile(z, x, y)) {
                            val bytes = fetch(source, z, x, y)
                            if (bytes != null) writer.putTile(z, x, y, bytes) else failed++
                            delay(throttleMs)
                        }
                        done++
                        if (done % PROGRESS_EVERY == 0L || done == total) {
                            onProgress(Progress(done, total, failed))
                        }
                        y++
                    }
                    x++
                }
            }
            onProgress(Progress(done, total, failed))
            writer.tileCount()
        }
    }

    private fun fetch(source: MapSource, z: Int, x: Int, y: Int): ByteArray? {
        val url = tileUrl(source, z, x, y)
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    companion object {
        const val USER_AGENT = "Rumb/1.0 (offline map cache)"
        private const val PROGRESS_EVERY = 25L

        /**
         * Resolves a [MapSource] template to a concrete tile URL. Substitutes `{z}/{x}/{y}`, expands
         * `{s}` to a rotating subdomain, and for TMS sources requests the inverted Y row
         * (`2^z - 1 - y`) while callers keep passing logical XYZ coordinates.
         */
        fun tileUrl(source: MapSource, z: Int, x: Int, y: Int): String {
            val serverY = if (source.scheme == MapSource.Scheme.TMS) (1 shl z) - 1 - y else y
            var url = source.url
                .replace("{z}", z.toString())
                .replace("{x}", x.toString())
                .replace("{y}", serverY.toString())
            source.subdomains?.takeIf { it.isNotEmpty() }?.let { subs ->
                url = url.replace("{s}", subs[((x + y) % subs.length + subs.length) % subs.length].toString())
            }
            return url
        }
    }
}
