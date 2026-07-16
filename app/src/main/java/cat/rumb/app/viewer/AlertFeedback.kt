package cat.rumb.app.viewer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Selectable synthesized beep patterns (no audio files). [tone] is a ToneGenerator constant. */
enum class BeepSound(val tone: Int, val durationMs: Int) {
    BEEP(ToneGenerator.TONE_PROP_BEEP, 200),
    DOUBLE(ToneGenerator.TONE_PROP_BEEP2, 250),
    ACK(ToneGenerator.TONE_PROP_ACK, 250),
    PROMPT(ToneGenerator.TONE_PROP_PROMPT, 300),
    CHIME(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300);

    companion object {
        fun byIndex(i: Int): BeepSound = entries.getOrElse(i) { DOUBLE }
    }
}

/** Physical feedback (haptic + tone) for viewer alerts. No audio resources required. */
object AlertFeedback {

    fun vibrate(context: Context, millis: Long = 350) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(millis)
        }
    }

    /** Short alert tone. Best-effort. */
    fun beep(sound: BeepSound = BeepSound.DOUBLE) = beeps(1, sound)

    /**
     * Plays [count] tones of [sound] in sequence (e.g. one per milestone). Uses the ALARM stream so it
     * is audible outdoors regardless of the media volume; best-effort but logs failures.
     */
    fun beeps(count: Int, sound: BeepSound = BeepSound.DOUBLE) {
        val n = count.coerceIn(1, 5)
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            Thread {
                // Small warm-up so the first startTone isn't dropped before the AudioTrack is ready.
                Thread.sleep(120)
                repeat(n) {
                    tone.startTone(sound.tone, sound.durationMs)
                    Thread.sleep((sound.durationMs + 150).toLong())
                }
                Thread.sleep(60) // let the last tone finish before releasing
                runCatching { tone.release() }
            }.start()
        }.onFailure { cat.rumb.app.data.debug.DebugLog.e("Audio", "beep fallit", it) }
    }

    /**
     * Lights out: the long tone as you cross a circuit's finish line, after the 3-2-1 beeps.
     * Deliberately NOT a [BeepSound] — that enum is the user's alert-sound picker, and the start
     * light isn't a preference. Long and high so it reads as "go", not as another countdown beep.
     */
    fun lapLightsOut() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            Thread {
                Thread.sleep(120)
                tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, LIGHTS_OUT_MS)
                Thread.sleep((LIGHTS_OUT_MS + 100).toLong())
                runCatching { tone.release() }
            }.start()
        }.onFailure { cat.rumb.app.data.debug.DebugLog.e("Audio", "to de sortida fallit", it) }
    }

    private const val LIGHTS_OUT_MS = 900
}
