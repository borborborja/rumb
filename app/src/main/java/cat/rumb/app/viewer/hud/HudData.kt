package cat.rumb.app.viewer.hud

/**
 * Everything the HUD renders, beyond the scalar [metrics]:
 * - [speedSeries]: recent speed samples (km/h) for the live speed sparkline.
 * - [elevationProfile]: elevation samples (m) of the followed route, or empty if not following.
 * - [routeProgress]: fraction [0,1] of the followed route already covered (marker on the profile).
 */
data class HudData(
    val metrics: LiveMetrics = LiveMetrics(),
    val speedSeries: List<Float> = emptyList(),
    // Recent sensor history for per-widget mini charts (FC, cadència, potència).
    val heartRateSeries: List<Float> = emptyList(),
    val cadenceSeries: List<Float> = emptyList(),
    val powerSeries: List<Float> = emptyList(),
    val elevationProfile: List<Float> = emptyList(),
    val routeProgress: Float = 0f,
    /** True when a route is being followed (enables the off-route warning). */
    val following: Boolean = false,
    /** Deviation (m) above which the off-route warning shows. */
    val offRouteThresholdM: Int = 40,
    /** Display units chosen by the user. */
    val units: Units = Units(),
    /** True while a native recording is paused (drives the record control's play button). */
    val isPaused: Boolean = false,
    /** When true, the lap (mark/end-lap) buttons show next to the record control in both views. */
    val lapManagementEnabled: Boolean = true,
    // Competition (ghost race) state — only meaningful when [competing] is true.
    val competing: Boolean = false,
    val ghostHalo: Boolean = true,
    val ghostShowSeconds: Boolean = true,
) {
    /** Whether the off-route warning banner should be shown right now. */
    val isOffRoute: Boolean
        get() = following && (metrics.offRouteMeters ?: 0.0) > offRouteThresholdM

    /** Race state vs the ghost: within ±[GhostState.EVEN_M] counts as even (blue). */
    val ghostState: GhostState?
        get() = GhostState.of(metrics.ghostDeltaMeters)
}

/** The face the ghost wears on the map when drawn as a figure rather than a plain dot. */
enum class GhostFace { LAUGHING, CRYING, NEUTRAL }

/** Whether the athlete is ahead of, behind, or even with the ghost. */
enum class GhostState(val colorHex: String) {
    AHEAD("#2ECC71"),
    BEHIND("#E63946"),
    EVEN("#3A86FF"),
    ;

    /**
     * The face the ghost wears — from the GHOST's point of view, because it is your rival and not
     * your coach. [AHEAD] means YOU are ahead, so the ghost is losing and cries; [BEHIND] means it
     * is beating you, so it laughs at you.
     *
     * Yes, this reads backwards on purpose: the halo goes green (good for you) exactly when the
     * ghost is in tears. Don't "fix" it — see GhostStateTest.
     */
    val face: GhostFace
        get() = when (this) {
            AHEAD -> GhostFace.CRYING
            BEHIND -> GhostFace.LAUGHING
            EVEN -> GhostFace.NEUTRAL
        }

    companion object {
        /** Within ±this many metres the race counts as even. */
        const val EVEN_M = 5.0

        /** Classifies a signed delta (+ = you are ahead of the ghost). Null delta = not racing. */
        fun of(deltaMeters: Double?): GhostState? = deltaMeters?.let {
            when {
                it > EVEN_M -> AHEAD
                it < -EVEN_M -> BEHIND
                else -> EVEN
            }
        }
    }
}
