package com.example.douyinautoliverecorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object SystemCuePlayer {
    @Volatile
    private var lastRecordingCueAtMs: Long = 0L

    fun playRecordingStarted(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecordingCueAtMs < 900L) {
            return
        }
        lastRecordingCueAtMs = now

        val appContext = context.applicationContext
        // Respect the phone's ringer mode: ring when normal, buzz when on vibrate, stay quiet when
        // silenced. A notification sound would not play audibly in vibrate/silent mode anyway.
        when (ringerMode(appContext)) {
            AudioManager.RINGER_MODE_NORMAL -> playDefaultNotificationCue(appContext)
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(appContext)
            else -> Unit
        }
    }

    private fun ringerMode(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioManager.RINGER_MODE_NORMAL
        return audioManager.ringerMode
    }

    private fun vibrate(context: Context) {
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) {
            return
        }
        runCatching {
            // Three long buzzes so it's hard to miss when the live stream starts.
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 600L, 200L, 600L, 200L, 600L), -1)
            vibrator.vibrate(effect)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun playDefaultNotificationCue(context: Context) {
        val played = runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return@runCatching false
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return@runCatching false
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            true
        }.getOrDefault(false)

        if (!played) {
            playFallbackBeep()
        }
    }

    private fun playFallbackBeep() {
        Thread {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }.getOrNull() ?: return@Thread
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                Thread.sleep(220L)
            } finally {
                tone.release()
            }
        }.start()
    }
}
