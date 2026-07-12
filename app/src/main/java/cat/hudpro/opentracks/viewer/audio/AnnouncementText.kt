package cat.hudpro.opentracks.viewer.audio

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Which values to include in a spoken progress announcement. */
data class AnnounceFields(
    val distanceTime: Boolean = true,
    val pace: Boolean = true,
    val elevation: Boolean = false,
    val heartRate: Boolean = false,
    val splitPace: Boolean = false,
)

/** Snapshot of the values available for an announcement. */
data class AnnounceSnapshot(
    val distanceKm: Double,
    val elapsedSeconds: Long,
    val paceMinPerKm: Double? = null,
    val speedKmh: Double? = null,
    val elevationGainM: Double? = null,
    val heartRateBpm: Double? = null,
    val splitPaceMinPerKm: Double? = null,
)

/** Localized word set for one language. */
private data class Words(
    val kmOne: String, val kmMany: String,
    val timeIntro: String, val hourOne: String, val hourMany: String,
    val minOne: String, val minMany: String, val sec: String,
    val paceIntro: String, val perKm: String, val kmh: String,
    val gainIntro: String, val meters: String,
    val hrIntro: String, val bpm: String,
    val splitIntro: String, val offRoute: String,
)

/** Builds localized spoken phrases for the announcement engine. Pure/testable. */
object AnnouncementText {

    fun progress(lang: AnnounceLang, fields: AnnounceFields, snap: AnnounceSnapshot): String {
        val w = words(lang)
        val parts = mutableListOf<String>()

        if (fields.distanceTime) {
            parts += distanceWords(w, snap.distanceKm)
            parts += "${w.timeIntro} ${durationWords(w, snap.elapsedSeconds)}"
        }
        if (fields.splitPace && snap.splitPaceMinPerKm != null) {
            parts += "${w.splitIntro} ${paceWords(w, snap.splitPaceMinPerKm)}"
        } else if (fields.pace) {
            when {
                snap.paceMinPerKm != null -> parts += "${w.paceIntro} ${paceWords(w, snap.paceMinPerKm)}"
                snap.speedKmh != null -> parts += "${snap.speedKmh.roundToInt()} ${w.kmh}"
            }
        }
        if (fields.elevation && snap.elevationGainM != null) {
            parts += "${w.gainIntro} ${snap.elevationGainM.roundToInt()} ${w.meters}"
        }
        if (fields.heartRate && snap.heartRateBpm != null) {
            parts += "${w.hrIntro} ${snap.heartRateBpm.roundToInt()} ${w.bpm}"
        }
        return parts.joinToString(". ")
    }

    fun offRoute(lang: AnnounceLang): String = words(lang).offRoute

    private fun distanceWords(w: Words, km: Double): String {
        val rounded = if (km % 1.0 == 0.0) km.roundToInt().toString() else String.format(Locale.US, "%.1f", km)
        val unit = if (km == 1.0) w.kmOne else w.kmMany
        return "$rounded $unit"
    }

    private fun durationWords(w: Words, seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) {
            "$h ${if (h == 1L) w.hourOne else w.hourMany} $m ${if (m == 1L) w.minOne else w.minMany}"
        } else {
            "$m ${if (m == 1L) w.minOne else w.minMany}"
        }
    }

    private fun paceWords(w: Words, paceMinPerKm: Double): String {
        var min = floor(paceMinPerKm).toInt()
        var sec = ((paceMinPerKm - min) * 60).roundToInt()
        if (sec == 60) { min++; sec = 0 }
        val minLabel = if (min == 1) w.minOne else w.minMany
        return if (sec == 0) {
            "$min $minLabel ${w.perKm}"
        } else {
            "$min $minLabel $sec ${w.sec} ${w.perKm}"
        }
    }

    private fun words(lang: AnnounceLang): Words = when (lang) {
        AnnounceLang.CA -> Words(
            "quilòmetre", "quilòmetres", "temps", "hora", "hores", "minut", "minuts", "segons",
            "ritme", "per quilòmetre", "quilòmetres per hora", "desnivell", "metres",
            "freqüència cardíaca", "pulsacions per minut", "últim quilòmetre", "Fora de ruta",
        )
        AnnounceLang.ES -> Words(
            "kilómetro", "kilómetros", "tiempo", "hora", "horas", "minuto", "minutos", "segundos",
            "ritmo", "por kilómetro", "kilómetros por hora", "desnivel", "metros",
            "frecuencia cardíaca", "pulsaciones por minuto", "último kilómetro", "Fuera de ruta",
        )
        AnnounceLang.EN -> Words(
            "kilometer", "kilometers", "time", "hour", "hours", "minute", "minutes", "seconds",
            "pace", "per kilometer", "kilometers per hour", "elevation gain", "meters",
            "heart rate", "beats per minute", "last kilometer", "Off route",
        )
        AnnounceLang.FR -> Words(
            "kilomètre", "kilomètres", "temps", "heure", "heures", "minute", "minutes", "secondes",
            "allure", "par kilomètre", "kilomètres par heure", "dénivelé", "mètres",
            "fréquence cardiaque", "battements par minute", "dernier kilomètre", "Hors itinéraire",
        )
        AnnounceLang.DE -> Words(
            "Kilometer", "Kilometer", "Zeit", "Stunde", "Stunden", "Minute", "Minuten", "Sekunden",
            "Tempo", "pro Kilometer", "Kilometer pro Stunde", "Höhenmeter", "Meter",
            "Herzfrequenz", "Schläge pro Minute", "letzter Kilometer", "Abseits der Route",
        )
        AnnounceLang.IT -> Words(
            "chilometro", "chilometri", "tempo", "ora", "ore", "minuto", "minuti", "secondi",
            "passo", "al chilometro", "chilometri all'ora", "dislivello", "metri",
            "frequenza cardiaca", "battiti al minuto", "ultimo chilometro", "Fuori percorso",
        )
    }
}
