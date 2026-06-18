package com.example.douyinautoliverecorder

import android.content.Context

object AppText {
    private fun zh(context: Context): Boolean {
        val locales = context.resources.configuration.locales
        return locales.size() > 0 && locales[0].language.startsWith("zh")
    }

    fun appTitle(@Suppress("UNUSED_PARAMETER") context: Context) = "Douyin Live Auto Recorder"
    fun startMonitoring(context: Context) = if (zh(context)) "\u5f00\u59cb\u76d1\u63a7" else "Start Monitoring"
    fun stopMonitoring(context: Context) = if (zh(context)) "\u505c\u6b62\u76d1\u63a7" else "Stop Monitoring"
    fun running(context: Context) = if (zh(context)) "\u8fd0\u884c\u4e2d" else "Running"
    fun stopped(context: Context) = if (zh(context)) "\u5df2\u505c\u6b62" else "Stopped"
    fun roomsTab(context: Context) = if (zh(context)) "\u76f4\u64ad\u95f4" else "Rooms"
    fun settingsTab(context: Context) = if (zh(context)) "\u8bbe\u7f6e" else "Settings"
    fun roomHint(context: Context) = if (zh(context)) "\u6296\u97f3\u76f4\u64ad\u94fe\u63a5\u6216\u6296\u97f3\u53f7" else "Douyin room link or ID"
    fun addRoomShort(context: Context) = if (zh(context)) "\u6dfb\u52a0" else "Add"
    fun monitoredRooms(context: Context, count: Int) = if (zh(context)) "\u76d1\u63a7\u76f4\u64ad\u95f4: $count" else "Monitored rooms: $count"
    fun dragSortHint(context: Context) = if (zh(context)) "\u957f\u6309\u623f\u95f4\u5361\u62d6\u62fd\u6392\u5e8f\uff0c\u5176\u4ed6\u5361\u7247\u4f1a\u81ea\u52a8\u8865\u4f4d" else "Long press a room card to reorder and let other cards slide into place"
    fun dragSortLabel(context: Context) = if (zh(context)) "\u957f\u6309\u62d6\u52a8" else "Long press"
    fun checkIntervalLabel(context: Context) = if (zh(context)) "\u68c0\u6d4b\u95f4\u9694 (\u79d2)" else "Check interval (seconds)"
    fun qualityLabel(context: Context) = if (zh(context)) "\u5f55\u5236\u5206\u8fa8\u7387" else "Recording resolution"
    fun bitrateLabel(context: Context) = if (zh(context)) "\u5f55\u5236\u7801\u7387" else "Recording bitrate"
    fun storageLabel(context: Context) = if (zh(context)) "\u5b58\u50a8\u4f4d\u7f6e" else "Storage location"
    fun outputFilesLabel(context: Context) = if (zh(context)) "\u8f93\u51fa\u6587\u4ef6" else "Output files"
    fun rawMp4OnlyLabel(context: Context) = if (zh(context)) "\u4ec5\u539f\u59cb MP4" else "Raw MP4 only"
    fun rawAndDanmuLabel(context: Context) = if (zh(context)) "\u539f\u59cb MP4 + \u5f39\u5e55\u7248 Beta" else "Raw MP4 + comments overlay Beta"
    fun storageMode(context: Context, mode: StorageMode) = when (mode) {
        StorageMode.PUBLIC_MEDIA -> if (zh(context)) "\u672c\u5730\u5b58\u50a8(\u76f8\u518c)" else "Local (Gallery)"
        StorageMode.DOCUMENT_TREE -> if (zh(context)) "SD\u5361/\u6587\u4ef6\u5939" else "SD Card / Folder"
    }
    fun liveCueLabel(context: Context) = if (zh(context)) "\u5f00\u64ad/\u5f00\u59cb\u5f55\u5236\u63d0\u793a\u97f3" else "Alert sound when recording starts"
    fun liveCueHint(context: Context) = if (zh(context)) "\u68c0\u6d4b\u5230\u5f00\u64ad\u5e76\u5f00\u59cb\u5f55\u5236\u65f6\u63d0\u9192\u4e00\u6b21\uff1a\u54cd\u94c3\u6a21\u5f0f\u54cd\u58f0\uff0c\u9707\u52a8\u6a21\u5f0f\u9707\u52a8\uff0c\u9759\u97f3\u5219\u4e0d\u63d0\u9192" else "Alerts once when a live stream is detected and recording starts: sound in normal mode, vibration in vibrate mode, nothing when silenced"
    fun scheduleLabel(context: Context) = if (zh(context)) "\u76d1\u63a7\u65f6\u95f4\u6bb5" else "Monitoring window"
    fun scheduleEnabled(context: Context) = if (zh(context)) "\u542f\u7528\u65f6\u95f4\u6bb5\u9650\u5236" else "Enable schedule window"
    fun scheduleStartLabel(context: Context) = if (zh(context)) "\u5f00\u59cb\u65f6\u95f4" else "Start time"
    fun scheduleEndLabel(context: Context) = if (zh(context)) "\u7ed3\u675f\u65f6\u95f4" else "End time"
    fun scheduleHint(context: Context) = if (zh(context)) "\u70b9\u51fb\u4e0b\u65b9\u65f6\u95f4\u6309\u94ae\uff0c\u6eda\u52a8\u9009\u62e9\u5f00\u59cb\u548c\u7ed3\u675f\u65f6\u95f4" else "Tap the time buttons below and scroll to choose start and end time"
    fun roomScheduleEnabled(context: Context) = if (zh(context)) "\u542f\u7528\u623f\u95f4\u65f6\u95f4\u6bb5" else "Enable room schedule"
    fun invalidTime(context: Context) = if (zh(context)) "\u65f6\u95f4\u683c\u5f0f\u65e0\u6548" else "Invalid time"
    fun waitingForWindow(context: Context, start: String, end: String) = if (zh(context)) {
        "\u5f53\u524d\u4e0d\u5728\u76d1\u63a7\u65f6\u95f4\u6bb5\u5185\uff0c\u7b49\u5f85 $start - $end"
    } else {
        "Outside monitoring window. Waiting for $start - $end"
    }
    fun selectFolder(context: Context) = if (zh(context)) "\u9009\u62e9\u6587\u4ef6\u5939" else "Select Folder"
    fun noFolderSelected(context: Context) = if (zh(context)) "\u672a\u9009\u62e9\u6587\u4ef6\u5939" else "No folder selected"
    fun saveSettings(context: Context) = if (zh(context)) "\u4fdd\u5b58\u8bbe\u7f6e" else "Save Settings"
    fun settingsSaved(context: Context) = if (zh(context)) "\u8bbe\u7f6e\u5df2\u4fdd\u5b58" else "Settings saved"
    fun invalidInterval(context: Context) = if (zh(context)) "\u95f4\u9694\u65e0\u6548" else "Invalid interval"
    fun selectStorageFolderFirst(context: Context) = if (zh(context)) "\u8bf7\u5148\u9009\u62e9\u5b58\u50a8\u6587\u4ef6\u5939" else "Select a storage folder first"
    fun storageFolderSelected(context: Context) = if (zh(context)) "\u5df2\u9009\u62e9\u5b58\u50a8\u6587\u4ef6\u5939" else "Storage folder selected"
    fun roomRequired(context: Context) = if (zh(context)) "\u8bf7\u8f93\u5165\u76f4\u64ad\u95f4\u94fe\u63a5\u6216\u6296\u97f3\u53f7" else "Room ID or URL is required"
    fun roomExists(context: Context) = if (zh(context)) "\u8be5\u76f4\u64ad\u95f4\u5df2\u5b58\u5728" else "Room already exists"
    fun statusLabel(context: Context, status: RoomStatus): String = when (status) {
        RoomStatus.IDLE -> if (zh(context)) "\u7a7a\u95f2" else "Idle"
        RoomStatus.CHECKING -> if (zh(context)) "\u68c0\u6d4b\u4e2d" else "Checking"
        RoomStatus.OFFLINE -> if (zh(context)) "\u672a\u5f00\u64ad" else "Offline"
        RoomStatus.LIVE -> if (zh(context)) "\u5df2\u5f00\u64ad" else "Live"
        RoomStatus.RECORDING -> if (zh(context)) "\u5f55\u5236\u4e2d" else "Recording"
        RoomStatus.ERROR -> if (zh(context)) "\u51fa\u9519" else "Error"
    }
    fun lastCheck(context: Context, time: CharSequence) = if (zh(context)) "\u4e0a\u6b21\u68c0\u6d4b: $time" else "Last check: $time"
    fun enabled(context: Context) = if (zh(context)) "\u5df2\u542f\u7528" else "Enabled"
    fun disabled(context: Context) = if (zh(context)) "\u5df2\u7981\u7528" else "Disabled"
    fun check(context: Context) = if (zh(context)) "\u68c0\u6d4b" else "Check"
    fun delete(context: Context) = if (zh(context)) "\u5220\u9664" else "Delete"
    fun checking(context: Context) = if (zh(context)) "\u68c0\u6d4b\u4e2d..." else "Checking..."
    fun offline(context: Context) = if (zh(context)) "\u672a\u5f00\u64ad" else "Offline"
    fun probeUncertain(context: Context) = if (zh(context)) "\u672c\u6b21\u68c0\u6d4b\u7ed3\u679c\u4e0d\u7a33\u5b9a\uff0c\u6682\u4e0d\u5224\u5b9a\u4e3a\u672a\u5f00\u64ad" else "Probe result is unstable. Offline verdict skipped."
    fun verificationRequired(context: Context) = if (zh(context)) "\u5df2\u88ab\u98ce\u63a7\uff0c\u9700\u4eba\u673a\u9a8c\u8bc1\uff08\u5c06\u81ea\u52a8\u91cd\u8bd5\uff09" else "Blocked by risk control - verification required (auto-retrying)"
    fun roomReady(context: Context) = if (zh(context)) "\u68c0\u6d4b\u5230\u5df2\u5f00\u64ad\uff0c\u53ef\u5f00\u59cb\u5f55\u5236" else "Live detected. Ready to record."
    fun liveNoStream(context: Context) = if (zh(context)) "\u5df2\u5f00\u64ad\uff0c\u4f46\u672a\u89e3\u6790\u5230\u53ef\u5f55\u5236\u6d41\u5730\u5740" else "Live detected but no supported stream URL"
    fun recordingMp4(context: Context) = if (zh(context)) "MP4\u5f55\u5236\u4e2d" else "Recording MP4"
    fun recordingSaved(context: Context) = if (zh(context)) "\u5df2\u4fdd\u5b58\u4e3a MP4" else "Saved as MP4"
    fun recordingStopped(context: Context) = if (zh(context)) "\u5df2\u505c\u6b62" else "Stopped"
    fun recordingStoppedSaved(context: Context) = if (zh(context)) "\u5df2\u505c\u6b62\u5e76\u4fdd\u5b58 MP4" else "Stopped and saved as MP4"
    fun recordingFailed(context: Context) = if (zh(context)) "\u5f55\u5236\u5931\u8d25" else "Recording failed"
    fun prepareStorageFailed(context: Context) = if (zh(context)) "\u521b\u5efa\u5b58\u50a8\u76ee\u6807\u5931\u8d25" else "Failed to prepare storage destination"
    fun startRecordingFailed(context: Context) = if (zh(context)) "\u542f\u52a8\u5f55\u5236\u5931\u8d25" else "Failed to start recording"
    fun startingMonitor(context: Context) = if (zh(context)) "\u6b63\u5728\u542f\u52a8\u76d1\u63a7..." else "Starting monitor..."
    fun noEnabledRooms(context: Context) = if (zh(context)) "\u6ca1\u6709\u542f\u7528\u7684\u76f4\u64ad\u95f4\uff0c\u8bf7\u5148\u6dfb\u52a0" else "No enabled rooms. Add a room to monitor."
    fun monitoringSummary(context: Context, rooms: Int, recordings: Int) = if (zh(context)) "\u76d1\u63a7 $rooms \u4e2a\u76f4\u64ad\u95f4\uff0c\u5f55\u5236\u4e2d $recordings \u4e2a" else "Monitoring $rooms room(s), recording $recordings"
    fun monitorRunningTitle(context: Context) = if (zh(context)) "\u6296\u97f3\u76d1\u63a7\u8fdb\u884c\u4e2d" else "Douyin monitor running"
    fun monitorChannelName(context: Context) = if (zh(context)) "\u76f4\u64ad\u76d1\u63a7" else "Live monitor"
    fun monitorChannelDescription(context: Context) = if (zh(context)) "\u524d\u53f0\u76f4\u64ad\u76d1\u63a7\u670d\u52a1" else "Foreground monitoring service"
    fun recordingChannelName(context: Context) = if (zh(context)) "\u5f55\u5236\u4e2d\u76f4\u64ad\u95f4" else "Recording rooms"
    fun recordingChannelDescription(context: Context) = if (zh(context)) "\u5f55\u5236\u4e2d\u7684\u623f\u95f4\u5934\u50cf\u548c\u8fdb\u5ea6\u901a\u77e5" else "Ongoing recording room notifications with room avatars"
    fun eventChannelName(context: Context) = if (zh(context)) "\u5f55\u5236\u901a\u77e5" else "Recording events"
    fun eventChannelDescription(context: Context) = if (zh(context)) "\u5f55\u5236\u5f00\u59cb\u6216\u72b6\u6001\u901a\u77e5" else "Recording start or status notifications"
    fun stop(context: Context) = if (zh(context)) "\u505c\u6b62" else "Stop"
}






