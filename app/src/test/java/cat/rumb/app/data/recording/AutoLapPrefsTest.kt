package cat.rumb.app.data.recording

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AutoLapPrefsTest {

    @Test
    fun lapManagementOffSilencesEveryAutomaticLap() {
        // The whole point of the master switch: off means no laps, no matter what the rest says.
        val r = AutoLapPrefs.resolve(
            lapManagement = false, circuit = false,
            byPosition = true, everyM = 1000f, detectLoop = true,
        )
        assertThat(r.byPosition).isFalse()
        assertThat(r.everyM).isEqualTo(0.0)
        assertThat(r.detectLoop).isFalse()
    }

    @Test
    fun offEvenDuringACircuitLeavesTheseFieldsClear() {
        // presetLapLine (set elsewhere) still laps a competition; these free-lap fields stay off.
        val r = AutoLapPrefs.resolve(
            lapManagement = false, circuit = true,
            byPosition = true, everyM = 1000f, detectLoop = true,
        )
        assertThat(r).isEqualTo(AutoLapPrefs(byPosition = false, everyM = 0.0, detectLoop = false))
    }

    @Test
    fun onOutsideACircuitPassesEverythingThrough() {
        val r = AutoLapPrefs.resolve(
            lapManagement = true, circuit = false,
            byPosition = true, everyM = 1000f, detectLoop = true,
        )
        assertThat(r.byPosition).isTrue()
        assertThat(r.everyM).isEqualTo(1000.0)
        assertThat(r.detectLoop).isTrue()
    }

    @Test
    fun onInsideACircuitDropsDistanceSplitsAndLoopDetection() {
        // Unchanged pre-switch behaviour: the meta owns the laps, so splits and detection step aside.
        val r = AutoLapPrefs.resolve(
            lapManagement = true, circuit = true,
            byPosition = true, everyM = 1000f, detectLoop = true,
        )
        assertThat(r.everyM).isEqualTo(0.0)
        assertThat(r.detectLoop).isFalse()
        // byPosition is irrelevant in a circuit (presetLapLine wins) but is passed through untouched.
        assertThat(r.byPosition).isTrue()
    }

    @Test
    fun offBeatsAnEnabledSportSplit() {
        // The exact reported case: 1 km splits set for the sport, but lap management turned off.
        val r = AutoLapPrefs.resolve(
            lapManagement = false, circuit = false,
            byPosition = false, everyM = 1000f, detectLoop = false,
        )
        assertThat(r.everyM).isEqualTo(0.0)
    }
}
