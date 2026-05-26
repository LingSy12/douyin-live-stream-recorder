package com.example.douyinautoliverecorder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

class LiveMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycleMutex = Mutex()
    private var monitorJob: Job? = null
    private var notificationJob: Job? = null
    private lateinit var resolver: DouyinLiveResolver
    private lateinit var webProbe: WebViewLiveProbe
    private lateinit var recordingEngine: RecordingEngine
    private lateinit var danmuRecorder: DanmuRecorder
    private val roomNotificationLock = Any()
    private val roomNotificationIds = mutableSetOf<Int>()
    private val notificationAvatarCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val statusBarAvatarIconCache = java.util.concurrent.ConcurrentHashMap<String, IconCompat>()
    private val avatarFetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val avatarHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val backgroundOverlayCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    private var stopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        webProbe = WebViewLiveProbe(this)
        resolver = DouyinLiveResolver(webProbe = webProbe)
        recordingEngine = RecordingEngine(this, serviceScope)
        danmuRecorder = DanmuRecorder(this)
        RuntimeStateStore.setMonitorRunning(AppPrefs.getSettings(this).monitorEnabled)
        serviceScope.launch { webProbe.warmUp() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionIds.ACTION_STOP_MONITOR -> stopMonitoring()
            ActionIds.ACTION_TRIGGER_NOW -> triggerMonitorNow()
            ActionIds.ACTION_STOP_ROOM -> stopRoomFromNotification(intent.getStringExtra(ActionIds.EXTRA_ROOM_ID))
            else -> startMonitoring()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        notificationJob?.cancel()
        recordingEngine.stopAll()
        danmuRecorder.stopAll(publish = false).forEach { result ->
            result.recording?.let(StorageHelper::discardRecording)
        }
        clearRoomNotifications()
        clearEventNotification()
        webProbe.destroy()
        serviceScope.cancel()
        releaseWakeLock()
        RuntimeStateStore.setMonitorRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Keeps the CPU awake so the monitor loop's [delay] keeps ticking.
     * Without this, Android Doze suspends the coroutine timer and a stream that goes live can be
     * missed for many minutes. Effective only when the app is exempt from battery optimization.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
            .onFailure { Log.w(TAG, "wake lock acquire failed: ${it.message}") }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) {
                lock.release()
            }
        }.onFailure { Log.w(TAG, "wake lock release failed: ${it.message}") }
        wakeLock = null
    }

    private fun startMonitoring() {
        AppPrefs.setMonitorEnabled(this, true)
        RuntimeStateStore.setMonitorRunning(true)
        RuntimeStateStore.reconcileRecordingStates(activeRecordingRoomIds())
        stopRequested = false
        acquireWakeLock()
        Log.d(TAG, "startMonitoring called")

        startForegroundCompat(
            buildPersistentNotification(
                NotificationStatus(
                    title = AppText.monitorRunningTitle(this),
                    message = AppText.startingMonitor(this)
                )
            )
        )
        ensureNotificationUpdates()
        publishPersistentStatus()

        if (monitorJob?.isActive == true) {
            requestImmediateCycle()
            return
        }

        monitorJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    runMonitorCycleLocked()
                }.onFailure { error ->
                    handleMonitorFailure(error)
                }

                val intervalSeconds = AppPrefs.getSettings(this@LiveMonitorService)
                    .checkIntervalSeconds
                    .coerceIn(
                        AppPrefs.MIN_CHECK_INTERVAL_SECONDS,
                        AppPrefs.MAX_CHECK_INTERVAL_SECONDS
                    )
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private fun triggerMonitorNow() {
        if (!AppPrefs.getSettings(this).monitorEnabled) {
            return
        }
        if (monitorJob?.isActive != true) {
            startMonitoring()
            return
        }
        requestImmediateCycle()
    }

    private fun requestImmediateCycle() {
        serviceScope.launch {
            runCatching {
                runMonitorCycleLocked()
            }.onFailure { error ->
                handleMonitorFailure(error)
            }
        }
    }

    private suspend fun runMonitorCycleLocked() {
        cycleMutex.withLock {
            runMonitorCycle()
        }
    }

    private fun ensureNotificationUpdates() {
        if (notificationJob?.isActive == true) {
            return
        }
        notificationJob = serviceScope.launch {
            while (isActive) {
                publishPersistentStatus()
                delay(NOTIFICATION_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopMonitoring() {
        AppPrefs.setMonitorEnabled(this, false)
        RuntimeStateStore.setMonitorRunning(false)
        monitorJob?.cancel()
        monitorJob = null
        stopRequested = true

        val now = System.currentTimeMillis()
        AppPrefs.getRooms(this).forEach { room ->
            val stopping = stopRoomRecording(room.id, now)
            if (!stopping) {
                danmuRecorder.stop(room.id, publish = false).recording?.let(StorageHelper::discardRecording)
                RuntimeStateStore.updateRoom(room.id) {
                    it.copy(
                        status = RoomStatus.IDLE,
                        message = AppText.stopped(this),
                        lastCheckedAtMs = now,
                        startedAtMs = null,
                        saveProgressPercent = null
                    )
                }
            }
        }

        publishPersistentStatus()
        finalizeStopIfIdle()
    }

    private suspend fun runMonitorCycle() {
        val settings = AppPrefs.getSettings(this)
        val allRooms = AppPrefs.getRooms(this)
        RuntimeStateStore.removeMissing(allRooms.map { it.id }.toSet())
        RuntimeStateStore.reconcileRecordingStates(activeRecordingRoomIds())
        Log.d(TAG, "runMonitorCycle rooms=${allRooms.size} scheduleEnabled=${settings.scheduleEnabled}")

        if (stopRequested || !settings.monitorEnabled) {
            publishPersistentStatus()
            return
        }

        stopDisabledRooms(allRooms)

        val rooms = allRooms.filter { it.enabled }
        if (rooms.isEmpty()) {
            publishPersistentStatus()
            return
        }

        val now = System.currentTimeMillis()
        rooms.forEach { room ->
            if (stopRequested) {
                return
            }
            val window = effectiveWindow(settings, room)
            if (!MonitorWindow.isWithinWindow(window.enabled, window.startMinutes, window.endMinutes, now)) {
                pauseRoomForWindow(room, window, now)
                return@forEach
            }
            runCatching {
                processRoom(room, settings)
            }.onFailure { error ->
                RuntimeStateStore.updateRoom(room.id) {
                    it.copy(
                        status = RoomStatus.ERROR,
                        message = error.message ?: AppText.recordingFailed(this),
                        lastCheckedAtMs = System.currentTimeMillis(),
                        startedAtMs = null,
                        saveProgressPercent = null
                    )
                }
            }
        }

        publishPersistentStatus()
    }

    private fun stopDisabledRooms(allRooms: List<MonitoredRoom>) {
        val now = System.currentTimeMillis()
        allRooms.asSequence()
            .filter { !it.enabled }
            .forEach { room ->
                val isActive = recordingEngine.isRecording(room.id) || danmuRecorder.isRecording(room.id)
                if (!isActive) {
                    return@forEach
                }
                val stopping = stopRoomRecording(room.id, now)
                if (!stopping) {
                    danmuRecorder.stop(room.id, publish = false).recording?.let(StorageHelper::discardRecording)
                    RuntimeStateStore.updateRoom(room.id) {
                        it.copy(
                            status = RoomStatus.IDLE,
                            message = "",
                            lastCheckedAtMs = now,
                            startedAtMs = null,
                            saveProgressPercent = null
                        )
                    }
                }
            }
    }

    private fun handleMonitorFailure(error: Throwable) {
        Log.e(TAG, "monitor cycle failed", error)
        val now = System.currentTimeMillis()
        val message = error.message ?: AppText.recordingFailed(this)
        val rooms = AppPrefs.getRooms(this).filter { it.enabled }

        rooms.forEach { room ->
            RuntimeStateStore.updateRoom(room.id) {
                it.copy(
                    status = RoomStatus.ERROR,
                    message = message,
                    lastCheckedAtMs = now,
                    startedAtMs = null,
                    saveProgressPercent = null
                )
            }
        }

        publishPersistent(NotificationStatus(AppText.monitorRunningTitle(this), message))
    }

    private fun effectiveWindow(settings: AppSettings, room: MonitoredRoom): EffectiveWindow {
        return if (room.scheduleEnabled) {
            EffectiveWindow(
                enabled = true,
                startMinutes = room.monitorWindowStartMinutes,
                endMinutes = room.monitorWindowEndMinutes
            )
        } else if (settings.scheduleEnabled) {
            EffectiveWindow(
                enabled = true,
                startMinutes = settings.monitorWindowStartMinutes,
                endMinutes = settings.monitorWindowEndMinutes
            )
        } else {
            EffectiveWindow(
                enabled = false,
                startMinutes = settings.monitorWindowStartMinutes,
                endMinutes = settings.monitorWindowEndMinutes
            )
        }
    }

    private fun pauseRoomForWindow(room: MonitoredRoom, window: EffectiveWindow, now: Long) {
        val message = AppText.waitingForWindow(
            this,
            MonitorWindow.formatMinutes(window.startMinutes),
            MonitorWindow.formatMinutes(window.endMinutes)
        )

        if (recordingEngine.isRecording(room.id)) {
            updateSavingProgress(room.id, 0, stopSavingMessage())
            recordingEngine.stop(room.id)
        } else {
            danmuRecorder.stop(room.id, publish = false).recording?.let(StorageHelper::discardRecording)
            RuntimeStateStore.updateRoom(room.id) {
                it.copy(
                    status = RoomStatus.IDLE,
                    message = message,
                    lastCheckedAtMs = now,
                    startedAtMs = null,
                    saveProgressPercent = null
                )
            }
        }
    }

    private fun scheduleProfileRefresh(room: MonitoredRoom, probe: ProbeResult) {
        if (!probe.isReliable) {
            return
        }
        if (!probe.roomDisplayName.isNullOrBlank() && !probe.avatarUrl.isNullOrBlank() && !probe.douyinId.isNullOrBlank()) {
            return
        }

        serviceScope.launch {
            val refreshed = withTimeoutOrNull(PROFILE_REFRESH_TIMEOUT_MS) {
                resolver.probe(room.normalizedInput, enrichProfile = true)
            } ?: return@launch

            AppPrefs.updateRoomMetadata(
                context = this@LiveMonitorService,
                roomId = room.id,
                displayName = refreshed.roomDisplayName,
                douyinId = refreshed.douyinId,
                avatarUrl = refreshed.avatarUrl,
                resolvedRoomId = refreshed.resolvedRoomId
            )
        }
    }

    private suspend fun processRoom(room: MonitoredRoom, settings: AppSettings) {
        if (stopRequested) {
            return
        }

        val now = System.currentTimeMillis()

        if (recordingEngine.isRecording(room.id)) {
            var startedAtMs = now
            RuntimeStateStore.updateRoom(room.id) { current ->
                val actualStartedAt = current.startedAtMs ?: now
                startedAtMs = actualStartedAt
                val liveStartedAt = current.lastLiveStartAtMs ?: actualStartedAt
                current.copy(
                    status = RoomStatus.RECORDING,
                    message = AppText.recordingMp4(this),
                    recordingPath = recordingEngine.outputPath(room.id),
                    lastCheckedAtMs = now,
                    startedAtMs = actualStartedAt,
                    saveProgressPercent = null,
                    lastLiveStartAtMs = liveStartedAt
                )
            }
            return
        }

        danmuRecorder.stop(room.id, publish = false).recording?.let(StorageHelper::discardRecording)

        RuntimeStateStore.updateRoom(room.id) {
            it.copy(
                status = RoomStatus.CHECKING,
                message = AppText.checking(this),
                lastCheckedAtMs = now,
                saveProgressPercent = null
            )
        }

        val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            resolver.probe(room.normalizedInput, enrichProfile = false)
        }

        if (probe == null) {
            RuntimeStateStore.updateRoom(room.id) {
                it.copy(
                    status = RoomStatus.ERROR,
                    message = "Probe timed out",
                    lastCheckedAtMs = System.currentTimeMillis(),
                    startedAtMs = null,
                    saveProgressPercent = null
                )
            }
            return
        }

        if (probe.blockedByVerification) {
            RuntimeStateStore.updateRoom(room.id) {
                it.copy(
                    status = RoomStatus.ERROR,
                    message = AppText.verificationRequired(this),
                    lastCheckedAtMs = System.currentTimeMillis(),
                    startedAtMs = null,
                    saveProgressPercent = null
                )
            }
            return
        }

        AppPrefs.updateRoomMetadata(
            context = this,
            roomId = room.id,
            displayName = probe.roomDisplayName,
            douyinId = probe.douyinId,
            avatarUrl = probe.avatarUrl,
            resolvedRoomId = probe.resolvedRoomId
        )
        scheduleProfileRefresh(room, probe)
        val roomLabel = probe.roomDisplayName ?: room.displayName ?: probe.resolvedRoomId ?: room.input

        if (!probe.isLive) {
            val checkedAt = System.currentTimeMillis()
            if (probe.shouldShowOfflineStatus()) {
                RuntimeStateStore.updateRoom(room.id) { current ->
                    val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                    val liveEndAt = if (wasLive) checkedAt else current.lastLiveEndAtMs
                    current.copy(
                        status = RoomStatus.OFFLINE,
                        message = AppText.offline(this),
                        lastCheckedAtMs = checkedAt,
                        startedAtMs = null,
                        saveProgressPercent = null,
                        lastLiveEndAtMs = liveEndAt
                    )
                }
                return
            }
            if (!probe.isReliable) {
                RuntimeStateStore.updateRoom(room.id) { current ->
                    current.copy(
                        status = RoomStatus.ERROR,
                        message = probe.message ?: AppText.probeUncertain(this),
                        lastCheckedAtMs = checkedAt,
                        startedAtMs = null,
                        saveProgressPercent = null
                    )
                }
                return
            }
            RuntimeStateStore.updateRoom(room.id) { current ->
                val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                val liveEndAt = if (wasLive) checkedAt else current.lastLiveEndAtMs
                current.copy(
                    status = RoomStatus.OFFLINE,
                    message = AppText.offline(this),
                    lastCheckedAtMs = checkedAt,
                    startedAtMs = null,
                    saveProgressPercent = null,
                    lastLiveEndAtMs = liveEndAt
                )
            }
            return
        }

        val candidateStreams = StreamSelector.orderedUrls(probe.streamUrls, settings.quality, settings.bitrate)
        if (candidateStreams.isEmpty()) {
            val checkedAt = System.currentTimeMillis()
            RuntimeStateStore.updateRoom(room.id) { current ->
                val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                val liveStartAt = if (!wasLive) checkedAt else current.lastLiveStartAtMs
                current.copy(
                    status = RoomStatus.ERROR,
                    message = AppText.liveNoStream(this),
                    lastCheckedAtMs = checkedAt,
                    saveProgressPercent = null,
                    lastLiveStartAtMs = liveStartAt
                )
            }
            return
        }
        // Used by RecordingEngine to find a fresh stream URL when a pull URL drops mid-broadcast,
        // so a brief interruption continues the same recording instead of ending it.
        // Returns fresh URLs while live, or an empty list once the broadcast is confirmed over.
        val reprobeStreams: suspend () -> List<String> = reprobe@{
            var attempt = 0
            while (attempt < REPROBE_MAX_ATTEMPTS) {
                if (attempt > 0) {
                    delay(REPROBE_RETRY_DELAY_MS)
                }
                attempt += 1
                val freshProbe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    resolver.probe(room.normalizedInput, enrichProfile = false)
                }
                if (freshProbe != null) {
                    if (freshProbe.isLive) {
                        val freshStreams = StreamSelector.orderedUrls(
                            freshProbe.streamUrls,
                            settings.quality,
                            settings.bitrate
                        )
                        if (freshStreams.isNotEmpty()) {
                            return@reprobe freshStreams
                        }
                    } else if (freshProbe.shouldShowOfflineStatus()) {
                        return@reprobe emptyList()
                    }
                }
            }
            emptyList()
        }
        val recordingStartedAt = System.currentTimeMillis()
        val startResult = recordingEngine.start(
            roomId = room.id,
            streamUrls = candidateStreams,
            roomLabel = roomLabel,
            settings = settings,
            reprobeStreams = reprobeStreams
        ) { finishResult ->
            handleRecordingFinished(room, settings, finishResult)
        }

        if (startResult.started) {
            val danmuRoomUrl = probe.douyinId
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://live.douyin.com/$it" }
                ?: room.normalizedInput
            val danmuResult = danmuRecorder.start(
                roomId = room.id,
                roomUrl = danmuRoomUrl,
                roomLabel = roomLabel,
                settings = settings,
                baseFileName = startResult.baseFileName,
                startedAtMs = recordingStartedAt
            )
            if (!danmuResult.started) {
                Log.w(TAG, "danmu capture failed room=${room.id} error=${danmuResult.error}")
            }

            RuntimeStateStore.updateRoom(room.id) { current ->
                val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                val liveStartAt = if (!wasLive) recordingStartedAt else current.lastLiveStartAtMs
                current.copy(
                    status = RoomStatus.RECORDING,
                    message = AppText.recordingMp4(this),
                    recordingPath = startResult.outputPath,
                    lastCheckedAtMs = System.currentTimeMillis(),
                    startedAtMs = recordingStartedAt,
                    saveProgressPercent = null,
                    lastLiveStartAtMs = liveStartAt
                )
            }
            SystemCuePlayer.playRecordingStarted(this)
        } else {
            val checkedAt = System.currentTimeMillis()
            RuntimeStateStore.updateRoom(room.id) { current ->
                val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                val liveStartAt = if (!wasLive) checkedAt else current.lastLiveStartAtMs
                current.copy(
                    status = RoomStatus.ERROR,
                    message = startResult.error ?: AppText.startRecordingFailed(this),
                    lastCheckedAtMs = checkedAt,
                    saveProgressPercent = null,
                    lastLiveStartAtMs = liveStartAt
                )
            }
        }
    }

    private fun handleRecordingFinished(
        room: MonitoredRoom,
        settings: AppSettings,
        finishResult: RecordingFinishResult
    ) {
        serviceScope.launch {
            updateSavingProgress(room.id, 5)
            val danmuResult = danmuRecorder.stop(room.id, publish = false)
            val finalResult = finalizeRecordingArtifacts(room.id, settings, finishResult, danmuResult)
            applyFinalState(room.id, finalResult)
        }
    }

    private fun applyFinalState(roomId: String, finalResult: FinalizedRecordingResult) {
        val roomEnabled = AppPrefs.getRooms(this).firstOrNull { it.id == roomId }?.enabled == true
        RuntimeStateStore.updateRoom(roomId) { current ->
            current.copy(
                status = when {
                    finalResult.isError -> RoomStatus.ERROR
                    stopRequested || !roomEnabled -> RoomStatus.IDLE
                    else -> RoomStatus.OFFLINE
                },
                message = finalResult.message,
                recordingPath = finalResult.outputPath ?: current.recordingPath,
                lastCheckedAtMs = System.currentTimeMillis(),
                startedAtMs = null,
                saveProgressPercent = null
            )
        }
        finalizeStopIfIdle()
    }

    private fun finalizeRecordingArtifacts(
        roomId: String,
        settings: AppSettings,
        finishResult: RecordingFinishResult,
        danmuResult: DanmuStopResult
    ): FinalizedRecordingResult {
        val danmuRecording = danmuResult.recording

        if (finishResult.isError) {
            danmuRecording?.let(StorageHelper::discardRecording)
            return FinalizedRecordingResult(
                isError = true,
                message = finishResult.message,
                outputPath = null
            )
        }

        val sourceRecording = finishResult.recording
        if (sourceRecording == null) {
            danmuRecording?.let(StorageHelper::discardRecording)
            return FinalizedRecordingResult(
                isError = false,
                message = finishResult.message,
                outputPath = null
            )
        }

        val shouldGenerateDanmu = settings.saveOutputMode == SaveOutputMode.RAW_AND_DANMU && danmuRecording != null
        val progressCallback: (Int) -> Unit = { percent ->
            updateSavingProgress(roomId, 15 + (percent.coerceIn(0, 100) * 85 / 100))
        }

        updateSavingProgress(roomId, 15)
        val publishedPath = StorageHelper.publishRecording(
            context = this,
            recording = sourceRecording,
            onProgress = progressCallback,
            keepSourceFile = shouldGenerateDanmu
        )
        if (publishedPath == null) {
            danmuRecording?.let(StorageHelper::discardRecording)
            sourceRecording.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
            return FinalizedRecordingResult(
                isError = true,
                message = AppText.prepareStorageFailed(this),
                outputPath = null
            )
        }

        if (shouldGenerateDanmu) {
            val companionDanmuRecording = danmuRecording ?: return FinalizedRecordingResult(
                isError = false,
                message = savedRawMessage(stopRequested),
                outputPath = publishedPath
            )
            launchBackgroundDanmuGeneration(
                roomId = roomId,
                settings = settings,
                sourceRecording = sourceRecording,
                danmuRecording = companionDanmuRecording
            )
            return FinalizedRecordingResult(
                isError = false,
                message = savedRawGeneratingDanmuMessage(stopRequested),
                outputPath = publishedPath
            )
        }

        danmuRecording?.let(StorageHelper::discardRecording)
        sourceRecording.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
        return FinalizedRecordingResult(
            isError = false,
            message = savedRawMessage(stopRequested),
            outputPath = publishedPath
        )
    }

    private fun finalizeStopIfIdle() {
        if (!stopRequested) {
            return
        }
        if (activeRecordingCount() > 0 || activeBackgroundOverlayRoomIds().isNotEmpty()) {
            publishPersistentStatus()
            return
        }

        notificationJob?.cancel()
        publishPersistent(
            NotificationStatus(
                title = AppText.monitorRunningTitle(this),
                message = AppText.stopped(this)
            )
        )
        clearRoomNotifications()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishPersistentStatus() {
        publishPersistent(buildNotificationStatus())
    }

    private fun publishPersistent(status: NotificationStatus) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(PERSISTENT_NOTIFICATION_ID, buildPersistentNotification(status))
            syncRoomNotifications()
        }.onFailure {
            Log.w(TAG, "publishPersistent failed: ${it.message}")
        }
    }

    private fun publishEvent(
        title: String,
        body: String,
        room: MonitoredRoom? = null,
        avatar: Bitmap? = null
    ) {
        val builder = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setTimeoutAfter(EVENT_NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(openAppPendingIntent())
        (avatar ?: roomNotificationLargeIcon(room))?.let(builder::setLargeIcon)
        val eventNotification = builder.build()

        runCatching {
            NotificationManagerCompat.from(this).notify(EVENT_NOTIFICATION_ID, eventNotification)
        }.onFailure {
            Log.w(TAG, "publishEvent failed: ${it.message}")
        }
    }

    private fun buildPersistentNotification(status: NotificationStatus): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setShowWhen(false)
            .setContentTitle(status.title)
            .setContentText(status.message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, AppText.stop(this), stopServicePendingIntent())

        val lines = status.lines
        if (!lines.isNullOrEmpty()) {
            val style = NotificationCompat.InboxStyle()
            lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        }

        return builder.build()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PERSISTENT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(PERSISTENT_NOTIFICATION_ID, notification)
        }
    }

    private fun stopServicePendingIntent(): PendingIntent {
        val intent = Intent(this, LiveMonitorService::class.java)
            .setAction(ActionIds.ACTION_STOP_MONITOR)
        return PendingIntent.getService(
            this,
            3001,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopRoomPendingIntent(roomId: String): PendingIntent {
        val intent = Intent(this, LiveMonitorService::class.java)
            .setAction(ActionIds.ACTION_STOP_ROOM)
            .putExtra(ActionIds.EXTRA_ROOM_ID, roomId)
        val requestCode = 4000 + (roomId.hashCode() and 0x0fffffff)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            3002,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun syncRoomNotifications(now: Long = System.currentTimeMillis()) {
        val manager = NotificationManagerCompat.from(this)
        val roomsById = AppPrefs.getRooms(this).associateBy { it.id }
        val states = RuntimeStateStore.roomStates.value
        val nextIds = mutableSetOf<Int>()

        states.forEach { (roomId, state) ->
            val savePercent = state.saveProgressPercent
            val startedAtMs = state.startedAtMs
            val isRecording = state.status == RoomStatus.RECORDING && startedAtMs != null
            val isSaving = savePercent != null
            if (!isRecording && !isSaving) {
                return@forEach
            }

            val notificationId = roomNotificationId(roomId)
            nextIds += notificationId
            val room = roomsById[roomId]
            val label = displayLabel(room)
            val channelId = if (isRecording) CHANNEL_RECORDINGS else CHANNEL_MONITOR
            val priority = if (isRecording) {
                NotificationCompat.PRIORITY_LOW
            } else {
                NotificationCompat.PRIORITY_LOW
            }
            val builder = NotificationCompat.Builder(this, channelId)
                .setShowWhen(false)
                .setContentTitle(label)
                .setPriority(priority)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(
                    if (isRecording) {
                        NotificationCompat.CATEGORY_SERVICE
                    } else {
                        NotificationCompat.CATEGORY_PROGRESS
                    }
                )
                .setContentIntent(openAppPendingIntent())
            roomNotificationSmallIcon(room, isRecording)?.let(builder::setSmallIcon)
                ?: builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            roomNotificationLargeIcon(room)?.let(builder::setLargeIcon)

            if (isSaving) {
                val percent = savePercent?.coerceIn(0, 100) ?: 0
                val text = if (isZh()) {
                    "$label \u4fdd\u5b58\u4e2d ${percent}%"
                } else {
                    "$label saving ${percent}%"
                }
                builder
                    .setContentText(text)
                    .setProgress(100, percent, false)
            } else if (startedAtMs != null) {
                val liveStartAtMs = state.lastLiveStartAtMs ?: startedAtMs
                val liveTime = DateFormat.format("HH:mm", liveStartAtMs)
                val text = if (isZh()) {
                    "\u5f00\u64ad $liveTime | \u5f55\u5236\u4e2d"
                } else {
                    "Live at $liveTime | recording"
                }
                builder
                    .setWhen(startedAtMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(false)
                    .setSubText(AppText.recordingMp4(this))
                    .setContentText(text)
                    .addAction(0, stopRoomActionLabel(), stopRoomPendingIntent(roomId))
            }

            runCatching {
                manager.notify(notificationId, builder.build())
            }.onFailure {
                Log.w(TAG, "publish room notification failed room=$roomId: ${it.message}")
            }
        }

        val staleIds = synchronized(roomNotificationLock) {
            val stale = roomNotificationIds.filterNot { it in nextIds }
            roomNotificationIds.clear()
            roomNotificationIds.addAll(nextIds)
            stale
        }
        staleIds.forEach { manager.cancel(it) }
    }

    private fun clearRoomNotifications() {
        val manager = NotificationManagerCompat.from(this)
        val ids = synchronized(roomNotificationLock) {
            val copy = roomNotificationIds.toList()
            roomNotificationIds.clear()
            copy
        }
        ids.forEach { manager.cancel(it) }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            AppText.monitorChannelName(this),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = AppText.monitorChannelDescription(this@LiveMonitorService)
            setShowBadge(false)
        }

        val eventChannel = NotificationChannel(
            CHANNEL_EVENTS,
            AppText.eventChannelName(this),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = AppText.eventChannelDescription(this@LiveMonitorService)
            setShowBadge(true)
        }

        val recordingChannel = NotificationChannel(
            CHANNEL_RECORDINGS,
            AppText.recordingChannelName(this),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = AppText.recordingChannelDescription(this@LiveMonitorService)
            setShowBadge(false)
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(eventChannel)
        manager.createNotificationChannel(recordingChannel)
    }

    private fun stopRoomFromNotification(roomId: String?) {
        if (roomId.isNullOrBlank()) {
            return
        }
        AppPrefs.setRoomEnabled(this, roomId, false)
        val now = System.currentTimeMillis()
        val stopping = stopRoomRecording(roomId, now)
        if (!stopping) {
            danmuRecorder.stop(roomId, publish = false).recording?.let(StorageHelper::discardRecording)
            RuntimeStateStore.updateRoom(roomId) {
                it.copy(
                    status = RoomStatus.IDLE,
                    message = AppText.stopped(this),
                    lastCheckedAtMs = now,
                    startedAtMs = null,
                    saveProgressPercent = null
                )
            }
        }
        publishPersistentStatus()
    }

    private fun launchBackgroundDanmuGeneration(
        roomId: String,
        settings: AppSettings,
        sourceRecording: PreparedRecording,
        danmuRecording: PreparedRecording
    ) {
        incrementBackgroundOverlay(roomId)
        serviceScope.launch {
            try {
                updateIdleRoomMessage(roomId, backgroundDanmuProgressMessage())
                val overlayResult = DanmuOverlayRenderer.finalizeVideo(
                    context = this@LiveMonitorService,
                    settings = settings,
                    videoRecording = sourceRecording,
                    danmuRecording = danmuRecording
                )

                if (!overlayResult.hadDanmu) {
                    overlayResult.recording.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
                    overlayResult.fallbackRecording?.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
                    updateIdleRoomMessage(roomId, backgroundDanmuFailedMessage())
                    Log.d(
                        TAG,
                        "background danmu room=$roomId hadDanmu=false overlayFailed=${overlayResult.overlayFailed} entryCount=${overlayResult.entryCount}"
                    )
                    return@launch
                }

                val companionFileName = companionDanmuFileName(sourceRecording.fileName)
                val companionRecording = StorageHelper.renameRecording(overlayResult.recording, companionFileName)
                    ?: overlayResult.recording.copy(fileName = companionFileName, displayPath = companionFileName)
                val publishedPath = StorageHelper.publishRecording(this@LiveMonitorService, companionRecording)
                overlayResult.fallbackRecording?.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)

                if (publishedPath != null) {
                    updateIdleRoomMessage(roomId, backgroundDanmuSavedMessage())
                } else {
                    updateIdleRoomMessage(roomId, backgroundDanmuFailedMessage())
                }

                Log.d(
                    TAG,
                    "background danmu room=$roomId hadDanmu=${overlayResult.hadDanmu} overlayFailed=${overlayResult.overlayFailed} entryCount=${overlayResult.entryCount} output=$publishedPath"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "background danmu failed room=$roomId", error)
                sourceRecording.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
                danmuRecording.takeIf { it.tempFile.exists() }?.let(StorageHelper::discardRecording)
                updateIdleRoomMessage(roomId, backgroundDanmuFailedMessage())
            } finally {
                decrementBackgroundOverlay(roomId)
            }
        }
    }

    private fun updateIdleRoomMessage(roomId: String, message: String) {
        RuntimeStateStore.updateRoom(roomId) { current ->
            if (current.status == RoomStatus.RECORDING || current.saveProgressPercent != null) {
                current
            } else {
                current.copy(
                    message = message,
                    lastCheckedAtMs = System.currentTimeMillis()
                )
            }
        }
        publishPersistentStatus()
    }

    private fun incrementBackgroundOverlay(roomId: String) {
        val next = (backgroundOverlayCounts[roomId] ?: 0) + 1
        backgroundOverlayCounts[roomId] = next
        publishPersistentStatus()
    }

    private fun decrementBackgroundOverlay(roomId: String) {
        val next = (backgroundOverlayCounts[roomId] ?: 0) - 1
        if (next > 0) {
            backgroundOverlayCounts[roomId] = next
        } else {
            backgroundOverlayCounts.remove(roomId)
        }
        publishPersistentStatus()
        finalizeStopIfIdle()
    }

    private fun activeBackgroundOverlayRoomIds(): Set<String> {
        return backgroundOverlayCounts.keys.toSet()
    }

    private fun roomNotificationId(roomId: String): Int {
        return 5000 + (roomId.hashCode() and 0x0fffffff)
    }

    private fun stopRoomRecording(roomId: String, now: Long): Boolean {
        if (!recordingEngine.isRecording(roomId)) {
            return false
        }
        RuntimeStateStore.updateRoom(roomId) {
            it.copy(
                status = RoomStatus.CHECKING,
                message = stopSavingMessage(),
                lastCheckedAtMs = now,
                saveProgressPercent = 0
            )
        }
        recordingEngine.stop(roomId)
        return true
    }

    private fun updateSavingProgress(roomId: String, percent: Int, message: String = savingProgressMessage(percent)) {
        RuntimeStateStore.updateRoom(roomId) {
            it.copy(
                status = RoomStatus.CHECKING,
                message = message,
                lastCheckedAtMs = System.currentTimeMillis(),
                saveProgressPercent = percent.coerceIn(0, 100)
            )
        }
        publishPersistentStatus()
    }

    private fun buildNotificationStatus(now: Long = System.currentTimeMillis()): NotificationStatus {
        val roomsById = AppPrefs.getRooms(this).associateBy { it.id }
        val states = RuntimeStateStore.roomStates.value

        val savingRooms = states.mapNotNull { (roomId, state) ->
            val percent = state.saveProgressPercent ?: return@mapNotNull null
            SavingStatus(room = roomsById[roomId], percent = percent)
        }
        if (savingRooms.isNotEmpty()) {
            val first = savingRooms.maxByOrNull { it.percent } ?: savingRooms.first()
            val label = displayLabel(first.room)
            val message = if (savingRooms.size == 1) {
                if (isZh()) "$label \u4fdd\u5b58\u4e2d ${first.percent}%" else "$label saving ${first.percent}%"
            } else {
                if (isZh()) "$label \u4fdd\u5b58\u4e2d ${first.percent}% \uff0c\u53e6\u5916 ${savingRooms.size - 1} \u4e2a" else "$label saving ${first.percent}%, plus ${savingRooms.size - 1} more"
            }
            return NotificationStatus(
                title = if (isZh()) "\u6296\u97f3\u5f55\u5236\u4fdd\u5b58\u4e2d" else "Saving Douyin recordings",
                message = message
            )
        }

        val recordingRooms = states.mapNotNull { (roomId, state) ->
            val startedAt = state.startedAtMs ?: return@mapNotNull null
            if (state.status != RoomStatus.RECORDING) {
                return@mapNotNull null
            }
            RecordingStatus(room = roomsById[roomId], startedAtMs = startedAt, liveStartedAtMs = state.lastLiveStartAtMs ?: startedAt)
        }
        if (recordingRooms.isNotEmpty()) {
            val labels = recordingRooms
                .sortedBy { it.startedAtMs }
                .map { displayLabel(it.room) }
                .filter { it.isNotBlank() }
            val message = if (labels.isNotEmpty()) {
                if (isZh()) labels.joinToString("\u3001") else labels.joinToString(", ")
            } else {
                displayLabel(recordingRooms.first().room)
            }
            return NotificationStatus(
                title = if (isZh()) "\u6296\u97f3\u76f4\u64ad\u5f55\u5236\u4e2d" else "Recording Douyin live",
                message = message,
                lines = if (labels.size > 1) labels else null
            )
        }

        val backgroundRooms = activeBackgroundOverlayRoomIds().mapNotNull { roomsById[it] }
        if (backgroundRooms.isNotEmpty()) {
            val first = backgroundRooms.first()
            val label = displayLabel(first)
            val message = if (backgroundRooms.size == 1) {
                if (isZh()) "$label \u6b63\u5728\u540e\u53f0\u751f\u6210\u5f39\u5e55\u7248" else "$label generating danmu in background"
            } else {
                if (isZh()) "$label \u6b63\u5728\u540e\u53f0\u751f\u6210\u5f39\u5e55\u7248 \uff0c\u53e6\u5916 ${backgroundRooms.size - 1} \u4e2a" else "$label generating danmu in background, plus ${backgroundRooms.size - 1} more"
            }
            return NotificationStatus(
                title = if (isZh()) "\u6b63\u5728\u751f\u6210\u5f39\u5e55\u7248" else "Generating danmu version",
                message = message
            )
        }

        val enabledCount = roomsById.values.count { it.enabled }
        if (stopRequested) {
            return NotificationStatus(
                title = if (isZh()) "\u6296\u97f3\u76d1\u63a7\u5df2\u505c\u6b62" else "Douyin monitoring stopped",
                message = if (enabledCount == 0) AppText.stopped(this) else stopSavingMessage()
            )
        }

        if (enabledCount == 0) {
            return NotificationStatus(
                title = AppText.monitorRunningTitle(this),
                message = AppText.noEnabledRooms(this)
            )
        }

        return NotificationStatus(
            title = AppText.monitorRunningTitle(this),
            message = AppText.monitoringSummary(this, enabledCount, activeRecordingCount())
        )
    }

    private fun clearEventNotification() {
        runCatching {
            NotificationManagerCompat.from(this).cancel(EVENT_NOTIFICATION_ID)
        }
    }

    private fun overlayProgressMessage(): String {
        return if (isZh()) {
            "\u6b63\u5728\u5408\u6210\u5f39\u5e55\u89c6\u9891..."
        } else {
            "Rendering danmu overlay..."
        }
    }

    private fun stopSavingMessage(): String {
        return if (isZh()) {
            "\u6b63\u5728\u505c\u6b62\u5e76\u4fdd\u5b58\u5f55\u5236..."
        } else {
            "Stopping and saving recordings..."
        }
    }

    private fun savingProgressMessage(percent: Int): String {
        return if (isZh()) {
            "\u6b63\u5728\u4fdd\u5b58\u5f55\u5236... ${percent.coerceIn(0, 100)}%"
        } else {
            "Saving recording... ${percent.coerceIn(0, 100)}%"
        }
    }

    private fun savedRawMessage(stopped: Boolean): String {
        return if (isZh()) {
            if (stopped) "\u5df2\u505c\u6b62\u5e76\u4fdd\u5b58\u539f\u59cb MP4" else "\u5df2\u4fdd\u5b58\u539f\u59cb MP4"
        } else {
            if (stopped) "Stopped and saved raw MP4" else "Saved raw MP4"
        }
    }

    private fun savedRawGeneratingDanmuMessage(stopped: Boolean): String {
        return if (isZh()) {
            if (stopped) {
                "\u5df2\u505c\u6b62\u5e76\u4fdd\u5b58\u539f\u59cb MP4\uff0c\u6b63\u5728\u540e\u53f0\u751f\u6210\u5f39\u5e55\u7248"
            } else {
                "\u5df2\u4fdd\u5b58\u539f\u59cb MP4\uff0c\u6b63\u5728\u540e\u53f0\u751f\u6210\u5f39\u5e55\u7248"
            }
        } else {
            if (stopped) {
                "Stopped and saved raw MP4, generating danmu in background"
            } else {
                "Saved raw MP4, generating danmu in background"
            }
        }
    }

    private fun backgroundDanmuProgressMessage(): String {
        return if (isZh()) {
            "\u6b63\u5728\u540e\u53f0\u751f\u6210\u5f39\u5e55\u7248..."
        } else {
            "Generating danmu version in background..."
        }
    }

    private fun backgroundDanmuSavedMessage(): String {
        return if (isZh()) {
            "\u5f39\u5e55\u7248\u5df2\u4fdd\u5b58"
        } else {
            "Danmu version saved"
        }
    }

    private fun backgroundDanmuFailedMessage(): String {
        return if (isZh()) {
            "\u539f\u59cb MP4 \u5df2\u4fdd\u5b58\uff0c\u5f39\u5e55\u7248\u751f\u6210\u5931\u8d25"
        } else {
            "Raw MP4 saved, danmu version failed"
        }
    }

    private fun stopRoomActionLabel(): String {
        return if (isZh()) {
            "\u505c\u6b62\u6b64\u76f4\u64ad\u95f4"
        } else {
            "Stop this room"
        }
    }

    private fun companionDanmuFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        val baseName = fileName.substringBeforeLast('.').ifBlank { fileName }
        return if (extension.isBlank()) {
            "${baseName}_danmu"
        } else {
            "${baseName}_danmu.$extension"
        }
    }

    private fun savedWithDanmuMessage(stopped: Boolean): String {
        return if (isZh()) {
            if (stopped) "\u5df2\u505c\u6b62\u5e76\u4fdd\u5b58\u542b\u5f39\u5e55 MP4" else "\u5df2\u4fdd\u5b58\u542b\u5f39\u5e55 MP4"
        } else {
            if (stopped) "Stopped and saved MP4 with danmu" else "Saved MP4 with danmu"
        }
    }

    private fun savedWithoutDanmuMessage(stopped: Boolean): String {
        return if (isZh()) {
            if (stopped) "\u5df2\u505c\u6b62\u5e76\u4fdd\u5b58 MP4\uff08\u5f39\u5e55\u5408\u6210\u5931\u8d25\uff09" else "\u5df2\u4fdd\u5b58 MP4\uff08\u5f39\u5e55\u5408\u6210\u5931\u8d25\uff09"
        } else {
            if (stopped) "Stopped and saved MP4 (danmu overlay failed)" else "Saved MP4 (danmu overlay failed)"
        }
    }

    private fun roomNotificationLargeIcon(room: MonitoredRoom?): Bitmap? {
        room ?: return null
        notificationAvatarCache[room.id]?.let { return it }
        val avatarUrl = room.avatarUrl ?: return null
        ensureRoomAvatar(room.id, avatarUrl)
        return null
    }

    private fun roomNotificationSmallIcon(room: MonitoredRoom?, isRecording: Boolean): IconCompat? {
        room ?: return null
        if (!isRecording) {
            return null
        }
        statusBarAvatarIconCache[room.id]?.let { return it }
        val avatar = roomNotificationLargeIcon(room) ?: return null
        val icon = createStatusBarAvatarIcon(avatar) ?: return null
        statusBarAvatarIconCache[room.id] = icon
        return icon
    }

    private fun fetchRoomAvatarForEvent(room: MonitoredRoom?): Bitmap? {
        room ?: return null
        notificationAvatarCache[room.id]?.let { return it }
        val avatarUrl = room.avatarUrl ?: return null
        val bitmap = fetchRoomAvatarBitmap(
            avatarUrl = avatarUrl,
            callTimeoutMs = EVENT_AVATAR_FETCH_TIMEOUT_MS
        ) ?: return null
        notificationAvatarCache[room.id] = bitmap
        statusBarAvatarIconCache.remove(room.id)
        return bitmap
    }

    private fun ensureRoomAvatar(roomId: String, avatarUrl: String) {
        val existing = avatarFetchJobs[roomId]
        if (existing?.isActive == true) {
            return
        }
        avatarFetchJobs[roomId] = serviceScope.launch {
            try {
                val bitmap = fetchRoomAvatarBitmap(avatarUrl) ?: return@launch
                notificationAvatarCache[roomId] = bitmap
                statusBarAvatarIconCache.remove(roomId)
                publishPersistentStatus()
            } finally {
                avatarFetchJobs.remove(roomId)
            }
        }
    }

    private fun fetchRoomAvatarBitmap(avatarUrl: String, callTimeoutMs: Long? = null): Bitmap? {
        return runCatching {
            val client = callTimeoutMs?.let {
                avatarHttpClient.newBuilder()
                    .callTimeout(it, TimeUnit.MILLISECONDS)
                    .build()
            } ?: avatarHttpClient
            val request = Request.Builder()
                .url(avatarUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                val stream = response.body?.byteStream() ?: return@use null
                val decoded = BitmapFactory.decodeStream(stream) ?: return@use null
                Bitmap.createScaledBitmap(decoded, 96, 96, true)
            }
        }.getOrNull()
    }

    private fun createStatusBarAvatarIcon(source: Bitmap): IconCompat? {
        val sizePx = (resources.displayMetrics.density * 20f).roundToInt().coerceAtLeast(40)
        val side = source.width.coerceAtMost(source.height).coerceAtLeast(1)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val squared = Bitmap.createBitmap(source, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(squared, sizePx, sizePx, true)
        if (squared !== source && squared !== scaled) {
            squared.recycle()
        }

        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val radius = sizePx / 2f
        val ringWidth = (sizePx * 0.08f).coerceAtLeast(2f)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                val distance = hypot(x + 0.5f - radius, y + 0.5f - radius)
                if (distance > radius) {
                    output.setPixel(x, y, Color.TRANSPARENT)
                    continue
                }

                val sourceColor = scaled.getPixel(x, y)
                val luminance = (
                    Color.red(sourceColor) * 0.299f +
                        Color.green(sourceColor) * 0.587f +
                        Color.blue(sourceColor) * 0.114f
                    )
                val featureAlpha = ((255f - luminance) * 1.35f).roundToInt().coerceIn(0, 255)
                val ringAlpha = if (distance >= radius - ringWidth) 170 else 0
                val finalAlpha = maxOf(featureAlpha, ringAlpha)
                output.setPixel(x, y, Color.argb(finalAlpha, 255, 255, 255))
            }
        }

        if (scaled !== source) {
            scaled.recycle()
        }
        return IconCompat.createWithBitmap(output)
    }

    private fun displayLabel(room: MonitoredRoom?): String {
        return room?.displayName ?: room?.douyinId ?: room?.input ?: if (isZh()) "\u76f4\u64ad\u95f4" else "Room"
    }

    private fun formatElapsed(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun isZh(): Boolean {
        val locales = resources.configuration.locales
        return locales.size() > 0 && locales[0].language.startsWith("zh")
    }

    private fun activeRecordingCount(): Int {
        return recordingEngine.activeCount()
    }

    private fun activeRecordingRoomIds(): Set<String> {
        return recordingEngine.activeRoomIds()
    }

    private data class FinalizedRecordingResult(
        val isError: Boolean,
        val message: String,
        val outputPath: String?
    )

    private data class EffectiveWindow(
        val enabled: Boolean,
        val startMinutes: Int,
        val endMinutes: Int
    )

    private data class NotificationStatus(
        val title: String,
        val message: String,
        val lines: List<String>? = null
    )

    private data class SavingStatus(
        val room: MonitoredRoom?,
        val percent: Int
    )

    private data class RecordingStatus(
        val room: MonitoredRoom?,
        val startedAtMs: Long,
        val liveStartedAtMs: Long? = null
    )

    companion object {
        private const val TAG = "LiveMonitorService"
        private const val PROBE_TIMEOUT_MS = 28_000L
        private const val PROFILE_REFRESH_TIMEOUT_MS = 25_000L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 10_000L
        private const val EVENT_AVATAR_FETCH_TIMEOUT_MS = 2_500L
        private const val EVENT_NOTIFICATION_TIMEOUT_MS = 20_000L
        private const val CHANNEL_MONITOR = "live_monitor_channel"
        private const val CHANNEL_RECORDINGS = "recording_room_channel_v2"
        private const val CHANNEL_EVENTS = "live_events_channel_v2"
        private const val PERSISTENT_NOTIFICATION_ID = 2201
        private const val EVENT_NOTIFICATION_ID = 2202
        private const val REPROBE_MAX_ATTEMPTS = 3
        private const val REPROBE_RETRY_DELAY_MS = 3_000L
        private const val WAKELOCK_TAG = "DouyinLiveRecorder::MonitorWakeLock"

        fun start(context: Context) {
            val intent = Intent(context, LiveMonitorService::class.java)
                .setAction(ActionIds.ACTION_START_MONITOR)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveMonitorService::class.java)
                .setAction(ActionIds.ACTION_STOP_MONITOR)
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.w(TAG, "stop request fallback: ${it.message}")
                context.stopService(Intent(context, LiveMonitorService::class.java))
            }
        }

        fun triggerNow(context: Context) {
            val intent = Intent(context, LiveMonitorService::class.java)
                .setAction(ActionIds.ACTION_TRIGGER_NOW)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "trigger request failed: ${it.message}")
            }
        }

        fun stopRoom(context: Context, roomId: String) {
            val intent = Intent(context, LiveMonitorService::class.java)
                .setAction(ActionIds.ACTION_STOP_ROOM)
                .putExtra(ActionIds.EXTRA_ROOM_ID, roomId)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "stop room request failed: ${it.message}")
            }
        }
    }
}






















































