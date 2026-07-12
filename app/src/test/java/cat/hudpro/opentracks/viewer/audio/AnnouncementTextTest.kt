package cat.hudpro.opentracks.viewer.audio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnnouncementTextTest {

    private val snap = AnnounceSnapshot(
        distanceKm = 5.0,
        elapsedSeconds = 27 * 60,
        paceMinPerKm = 5.5,
        elevationGainM = 120.0,
        heartRateBpm = 152.0,
        splitPaceMinPerKm = 5.5,
    )

    @Test
    fun spanishDistanceTimeAndPace() {
        val text = AnnouncementText.progress(
            AnnounceLang.ES,
            AnnounceFields(distanceTime = true, pace = true, splitPace = false),
            snap,
        )
        assertThat(text).contains("5 kilómetros")
        assertThat(text).contains("tiempo 27 minutos")
        assertThat(text).contains("ritmo 5 minutos 30 segundos por kilómetro")
    }

    @Test
    fun englishAndCatalanUnits() {
        val en = AnnouncementText.progress(AnnounceLang.EN, AnnounceFields(), snap)
        assertThat(en).contains("5 kilometers").contains("pace 5 minutes 30 seconds per kilometer")
        val ca = AnnouncementText.progress(AnnounceLang.CA, AnnounceFields(), snap)
        assertThat(ca).contains("5 quilòmetres")
    }

    @Test
    fun respectsFieldToggles() {
        val onlyElevHr = AnnouncementText.progress(
            AnnounceLang.ES,
            AnnounceFields(distanceTime = false, pace = false, elevation = true, heartRate = true),
            snap,
        )
        assertThat(onlyElevHr).contains("desnivel 120 metros")
        assertThat(onlyElevHr).contains("frecuencia cardíaca 152 pulsaciones por minuto")
        assertThat(onlyElevHr).doesNotContain("kilómetros")
    }

    @Test
    fun offRoutePhraseLocalized() {
        assertThat(AnnouncementText.offRoute(AnnounceLang.FR)).isEqualTo("Hors itinéraire")
        assertThat(AnnouncementText.offRoute(AnnounceLang.DE)).isEqualTo("Abseits der Route")
    }
}
