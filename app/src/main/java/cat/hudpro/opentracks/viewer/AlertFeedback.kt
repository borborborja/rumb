package cat.hudpro.opentracks.viewer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

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

    /** Short alert tone. Best-effort; ignores audio failures. */
    fun beep() = beeps(1)

    /** Plays [count] short tones in sequence (e.g. one per milestone). Best-effort. */
    fun beeps(count: Int) {
        val n = count.coerceIn(1, 5)
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            Thread {
                repeat(n) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                    Thread.sleep(400)
                }
                runCatching { tone.release() }
            }.start()
        }
    }
}
