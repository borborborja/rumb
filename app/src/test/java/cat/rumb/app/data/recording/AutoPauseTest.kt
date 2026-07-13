package cat.rumb.app.data.recording

import cat.rumb.app.data.opentracks.model.GeoPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AutoPauseTest {

    private val t0: Instant = Instant.parse("2026-07-12T10:00:00Z")
    private val here = GeoPoint(41.0, 2.0)

    @Test
    fun pausesAfterSustainedIdle() {
        val ap = AutoPause(idleSpeedMs = 0.4, idleAfterSec = 10)
        assertThat(ap.onFix(here, 0.1, t0, isPaused = false)).isEqualTo(AutoPause.Command.NONE)
        assertThat(ap.onFix(here, 0.1, t0.plusSeconds(5), isPaused = false)).isEqualTo(AutoPause.Command.NONE)
        assertThat(ap.onFix(here, 0.1, t0.plusSeconds(10), isPaused = false)).isEqualTo(AutoPause.Command.PAUSE)
        assertThat(ap.isAutoPaused).isTrue()
    }

    @Test
    fun fiveSecondThresholdPausesAtExactlyFiveSeconds() {
        val ap = AutoPause(idleSpeedMs = 0.4, idleAfterSec = 5)
        assertThat(ap.onFix(here, 0.1, t0, isPaused = false)).isEqualTo(AutoPause.Command.NONE)
        assertThat(ap.onFix(here, 0.1, t0.plusSeconds(4), isPaused = false)).isEqualTo(AutoPause.Command.NONE)
        assertThat(ap.onFix(here, 0.1, t0.plusSeconds(5), isPaused = false)).isEqualTo(AutoPause.Command.PAUSE)
        assertThat(ap.isAutoPaused).isTrue()
    }

    @Test
    fun movementResetsIdleTimer() {
        val ap = AutoPause(idleSpeedMs = 0.4, idleAfterSec = 10)
        ap.onFix(here, 0.1, t0, isPaused = false)
        ap.onFix(here, 3.0, t0.plusSeconds(5), isPaused = false) // moving again
        assertThat(ap.onFix(here, 0.1, t0.plusSeconds(12), isPaused = false)).isEqualTo(AutoPause.Command.NONE)
    }

    @Test
    fun autoResumesWhenMovedAway() {
        val ap = AutoPause(idleAfterSec = 10, resumeDistanceM = 12.0)
        ap.onFix(here, 0.1, t0, isPaused = false)
        ap.onFix(here, 0.1, t0.plusSeconds(10), isPaused = false) // → PAUSE
        // 4 m away: still paused.
        assertThat(ap.onFix(GeoPoint(41.000036, 2.0), 1.0, t0.plusSeconds(20), isPaused = true))
            .isEqualTo(AutoPause.Command.NONE)
        // ~22 m away: resume.
        assertThat(ap.onFix(GeoPoint(41.0002, 2.0), 1.5, t0.plusSeconds(30), isPaused = true))
            .isEqualTo(AutoPause.Command.RESUME)
        assertThat(ap.isAutoPaused).isFalse()
    }

    @Test
    fun neverAutoResumesManualPause() {
        val ap = AutoPause()
        ap.onManualOverride() // user paused manually
        // Even far away, a manual pause is respected.
        assertThat(ap.onFix(GeoPoint(41.01, 2.0), 3.0, t0, isPaused = true)).isEqualTo(AutoPause.Command.NONE)
    }
}
