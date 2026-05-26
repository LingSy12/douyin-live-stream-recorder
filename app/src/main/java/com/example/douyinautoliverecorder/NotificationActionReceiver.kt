package com.example.douyinautoliverecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ActionIds.ACTION_STOP_MONITOR) {
            LiveMonitorService.stop(context)
        }
    }
}

