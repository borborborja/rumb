package cat.rumb.app.viewer.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cat.rumb.app.viewer.AlertFeedback
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks announcements with the device TTS. Falls back to a beep when the requested language voice is
 * unavailable. Requests transient audio focus so background music ducks while speaking.
 */
class SpeechAnnouncer(context: Context, private var lang: AnnounceLang) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var ready = false
    private var languageOk = false

    // In-flight utterances. Audio focus is requested on 0→1 and abandoned on →0, so background music
    // ducks only while actually speaking (a transient-duck focus that's never abandoned keeps music
    // ducked for the whole session).
    private val speaking = AtomicInteger(0)
    private var utteranceSeq = 0

    private val tts = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLanguage()
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { releaseIfIdle() }
            @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) { releaseIfIdle() }
            override fun onError(utteranceId: String?, errorCode: Int) { releaseIfIdle() }
        })
    }

    private fun releaseIfIdle() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(null)
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
        if (speaking.getAndIncrement() == 0) {
            @Suppress("DEPRECATION")
            audio?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        // Unique id per utterance so onDone/onError balance the focus counter exactly.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "rumb-${utteranceSeq++}")
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        speaking.set(0)
        @Suppress("DEPRECATION")
        runCatching { audio?.abandonAudioFocus(null) }
    }
}
