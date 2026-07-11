package cat.hudpro.opentracks.viewer.hud

/**
 * Callbacks for the interactive HUD controls (map centering, orientation, zoom). In the viewer these
 * drive the MapLibre camera; in the designer preview they are no-ops ([disabled]).
 */
data class HudControls(
    val followEnabled: Boolean = true,
    val onRecenter: () -> Unit = {},
    val onToggleFollow: () -> Unit = {},
    val onNorth: () -> Unit = {},
    val onZoomIn: () -> Unit = {},
    val onZoomOut: () -> Unit = {},
) {
    companion object {
        val disabled = HudControls()
    }
}
