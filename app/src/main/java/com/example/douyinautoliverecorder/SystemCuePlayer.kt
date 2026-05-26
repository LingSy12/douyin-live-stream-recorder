package com.example.douyinautoliverecorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.SystemClock

object SystemCuePlayer {
    @Volatile
    private var lastRecordingCueAtMs: Long = 0L

    fun playRecordingStarted(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecordingCueAtMs < 900L) {
            return
        }
        lastRecordingCueAtMs = now
        playDefaultNotificationCue(context)
    }

    private fun playDefaultNotificationCue(context: Context) {
        val appContext = context.applicationContext
        val played = runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return@runCatching false
            val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return@runCatching false
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
