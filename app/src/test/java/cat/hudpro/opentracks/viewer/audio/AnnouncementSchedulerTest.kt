package cat.hudpro.opentracks.viewer.audio

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class AnnouncementSchedulerTest {

    @Test
    fun firesAtEachKilometerWithoutRepeating() {
        val s = AnnouncementScheduler(AnnounceConfig(byDistance = true, distanceStepKm = 1.0, byTime = false))
        assertThat(s.update(0.5, 180)).isEmpty()
        val at1 = s.update(1.02, 360)
        assertThat(at1).hasSize(1)
        assertThat(at1[0].reason).isEqualTo(AnnouncementScheduler.Reason.DISTANCE)
        assertThat(at1[0].distanceKm).isEqualTo(1.0)
        // No repeat before the next km.
        assertThat(s.update(1.5, 480)).isEmpty()
        assertThat(s.update(2.1, 720)).hasSize(1)
    }

    @Test
    fun computesSplitPaceOfLastKm() {
        val s = AnnouncementScheduler(AnnounceConfig(distanceStepKm = 1.0))
        s.update(1.0, 300) // first km in 300 s → 5:00 min/km
        val t2 = s.update(2.0, 630) // second km in 330 s → 5.5 min/km
        assertThat(t2).hasSize(1)
        assertThat(t2[0].splitPaceMinPerKm!!).isEqualTo(5.5, within(0.001))
    }

    @Test
    fun firesByTime() {
        val s = AnnouncementScheduler(AnnounceConfig(byDistance = false, byTime = true, timeStepMin = 10))
        assertThat(s.update(1.0, 599)).isEmpty()
        val t = s.update(1.2, 600)
        assertThat(t).hasSize(1)
        assertThat(t[0].reason).isEqualTo(AnnouncementScheduler.Reason.TIME)
    }

    @Test
    fun distanceAndTimeCanBothFire() {
        val s = AnnouncementScheduler(AnnounceConfig(byDistance = true, distanceStepKm = 1.0, byTime = true, timeStepMin = 5))
        val t = s.update(1.0, 300) // 1 km AND 5 min at once
        assertThat(t.map { it.reason }).contains(AnnouncementScheduler.Reason.DISTANCE, AnnouncementScheduler.Reason.TIME)
    }
}
