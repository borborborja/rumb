package cat.hudpro.opentracks.viewer.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import cat.hudpro.opentracks.viewer.AlertFeedback

/**
 * Speaks announcements with the device TTS. Falls back to a beep when the requested language voice is
 * unavailable. Requests transient audio focus so background music ducks while speaking.
 */
class SpeechAnnouncer(context: Context, private var lang: AnnounceLang) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var ready = false
    private var languageOk = false

    private val tts = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLanguage()
        }
    }

    fun setLanguage(newLang: AnnounceLang) {
        lang = newLang
        if (ready) applyLanguage()
    }

    private fun applyLanguage() {
        val result = tts.setLanguage(lang.locale)
        languageOk = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Speaks [text]; if TTS or the language isn't available, emits a beep instead. */
    fun speak(text: String) {
        if (!ready || !languageOk || text.isBlank()) {
            AlertFeedback.beep()
            return
        }
        @Suppress("DEPRECATION")
        audio?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "hudpro-${text.hashCode()}")
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
