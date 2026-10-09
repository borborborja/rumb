package cat.rumb.app.data.premium

/** Rejects stale callbacks after a disconnect or a newer query, without wedging the next query. */
internal class PremiumQueryLifecycle {
    private var generation = 0L
    var isInFlight = false
        private set

    fun begin(supersede: Boolean = false): Long? {
        if (isInFlight && !supersede) return null
        isInFlight = true
        return ++generation
    }

    /** A stale or duplicate callback cannot clear a newer request's in-flight state. */
    fun complete(request: Long): Boolean {
        if (!isInFlight || request != generation) return false
        isInFlight = false
        return true
    }

    fun invalidate() {
        generation++
        isInFlight = false
    }
}
