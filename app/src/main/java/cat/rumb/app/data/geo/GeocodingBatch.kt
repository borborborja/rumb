package cat.rumb.app.data.geo

/** Checks current consent before each queued geocoding request, including after a pacing delay. */
object GeocodingBatch {
    suspend fun <T> run(
        pending: Iterable<T>,
        isEnabled: () -> Boolean,
        process: suspend (T) -> Unit,
    ) {
        for (item in pending) {
            if (!isEnabled()) return
            process(item)
        }
    }
}
