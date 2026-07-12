package cat.hudpro.opentracks.viewer.audio

import java.util.Locale

/** Supported announcement languages (device TTS voice). */
enum class AnnounceLang(val code: String, val label: String, val locale: Locale) {
    CA("ca", "Català", Locale("ca", "ES")),
    ES("es", "Español", Locale("es", "ES")),
    EN("en", "English", Locale.ENGLISH),
    FR("fr", "Français", Locale.FRENCH),
    DE("de", "Deutsch", Locale.GERMAN),
    IT("it", "Italiano", Locale.ITALIAN),
    ;

    companion object {
        val DEFAULT = CA
        fun byCode(code: String?): AnnounceLang = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
