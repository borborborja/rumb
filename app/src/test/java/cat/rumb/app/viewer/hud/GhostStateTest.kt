package cat.rumb.app.viewer.hud

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GhostStateTest {

    // --- of(): classifying the signed delta -----------------------------------------------------

    @Test
    fun notRacingHasNoState() {
        assertThat(GhostState.of(null)).isNull()
    }

    @Test
    fun positiveDeltaMeansYouAreAhead() {
        assertThat(GhostState.of(6.0)).isEqualTo(GhostState.AHEAD)
        assertThat(GhostState.of(400.0)).isEqualTo(GhostState.AHEAD)
    }

    @Test
    fun negativeDeltaMeansYouAreBehind() {
        assertThat(GhostState.of(-6.0)).isEqualTo(GhostState.BEHIND)
        assertThat(GhostState.of(-400.0)).isEqualTo(GhostState.BEHIND)
    }

    @Test
    fun withinTheEvenBandItIsATie() {
        // The band is inclusive on both edges: exactly ±5 m still counts as even.
        assertThat(GhostState.of(0.0)).isEqualTo(GhostState.EVEN)
        assertThat(GhostState.of(GhostState.EVEN_M)).isEqualTo(GhostState.EVEN)
        assertThat(GhostState.of(-GhostState.EVEN_M)).isEqualTo(GhostState.EVEN)
        assertThat(GhostState.of(4.99)).isEqualTo(GhostState.EVEN)
        assertThat(GhostState.of(-4.99)).isEqualTo(GhostState.EVEN)
    }

    // --- face: the ghost is a rival, not a coach ------------------------------------------------

    @Test
    fun theGhostCriesWhenYouAreAheadOfIt() {
        // Deliberately inverted: AHEAD is good news for YOU, so the ghost is the one losing.
        // If this ever "looks wrong", read GhostState.face before changing it.
        assertThat(GhostState.AHEAD.face).isEqualTo(GhostFace.CRYING)
    }

    @Test
    fun theGhostLaughsWhileItIsBeatingYou() {
        assertThat(GhostState.BEHIND.face).isEqualTo(GhostFace.LAUGHING)
    }

    @Test
    fun theGhostKeepsAStraightFaceOnATie() {
        assertThat(GhostState.EVEN.face).isEqualTo(GhostFace.NEUTRAL)
    }

    @Test
    fun theFaceContradictsTheHaloColourOnPurpose() {
        // The halo goes green (you winning) exactly when the ghost is in tears, and red when it
        // laughs. Pinning it here so a "consistency fix" has to argue with a test.
        assertThat(GhostState.AHEAD.colorHex).isEqualTo("#2ECC71")
        assertThat(GhostState.AHEAD.face).isEqualTo(GhostFace.CRYING)
        assertThat(GhostState.BEHIND.colorHex).isEqualTo("#E63946")
        assertThat(GhostState.BEHIND.face).isEqualTo(GhostFace.LAUGHING)
    }

    // --- HudData still classifies through of() --------------------------------------------------

    @Test
    fun hudDataDerivesItsStateFromTheDelta() {
        assertThat(HudData(metrics = LiveMetrics(ghostDeltaMeters = 12.0)).ghostState)
            .isEqualTo(GhostState.AHEAD)
        assertThat(HudData(metrics = LiveMetrics(ghostDeltaMeters = -12.0)).ghostState)
            .isEqualTo(GhostState.BEHIND)
        assertThat(HudData(metrics = LiveMetrics(ghostDeltaMeters = 1.0)).ghostState)
            .isEqualTo(GhostState.EVEN)
        assertThat(HudData().ghostState).isNull()
    }
}
