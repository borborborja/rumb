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
    val elevationProfile: List<Float> = emptyList(),
    val routeProgress: Float = 0f,
)
