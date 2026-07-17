package cat.rumb.app.manager.screens

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AttemptColorTest {

    @Test
    fun eachOfTheFirstPositionsGetsItsOwnColour() {
        val first6 = (1..6).map { attemptColor(it) }
        assertThat(first6).doesNotHaveDuplicates()
    }

    @Test
    fun theColourFollowsTheRankAndNothingElse() {
        // The row, its line and its badge all call this with the same rank, so ticking or unticking
        // another attempt must never repaint yours.
        assertThat(attemptColor(2)).isEqualTo(attemptColor(2))
        assertThat(attemptColor(1)).isNotEqualTo(attemptColor(2))
    }

    @Test
    fun beyondThePaletteItWrapsInsteadOfCrashing() {
        assertThat(attemptColor(7)).isEqualTo(attemptColor(1))
        assertThat(attemptColor(13)).isEqualTo(attemptColor(1))
    }

    @Test
    fun aRankOfZeroStillYieldsAColour() {
        // rankOf falls back to 0 for an attempt that vanished mid-recomposition; it must not throw.
        assertThat(attemptColor(0)).isEqualTo(attemptColor(1))
    }

    @Test
    fun everyColourIsParseable() {
        (0..12).forEach { assertThat(attemptColor(it)).matches("#[0-9A-Fa-f]{6}") }
    }
}
