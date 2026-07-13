package cat.hudpro.opentracks.viewer.hud

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
) {
    /** Whether the off-route warning banner should be shown right now. */
    val isOffRoute: Boolean
        get() = following && (metrics.offRouteMeters ?: 0.0) > offRouteThresholdM
}
