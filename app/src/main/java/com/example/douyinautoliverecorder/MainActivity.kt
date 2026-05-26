package com.example.douyinautoliverecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // No-op.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()

        val initialSettings = AppPrefs.getSettings(this)
        RuntimeStateStore.setMonitorRunning(initialSettings.monitorEnabled)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen()
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecorderScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val webProbe = remember(context) { WebViewLiveProbe(context) }
    val resolver = remember(context) { DouyinLiveResolver(webProbe = webProbe) }
    DisposableEffect(webProbe) {
        onDispose { webProbe.destroy() }
    }
    val isZh = remember(context) {
        val locales = context.resources.configuration.locales
        locales.size() > 0 && locales[0].language.startsWith("zh")
    }

    var settings by remember { mutableStateOf(AppPrefs.getSettings(context)) }
    var rooms by remember { mutableStateOf(AppPrefs.getRooms(context)) }
    var roomInputValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var intervalInput by rememberSaveable { mutableStateOf(settings.checkIntervalSeconds.toString()) }
    var intervalSaveState by rememberSaveable { mutableStateOf(IntervalFieldState.SAVED) }
    var windowStartInput by rememberSaveable { mutableStateOf(MonitorWindow.formatMinutes(settings.monitorWindowStartMinutes)) }
    var windowEndInput by rememberSaveable { mutableStateOf(MonitorWindow.formatMinutes(settings.monitorWindowEndMinutes)) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var activeTimePicker by remember { mutableStateOf<SchedulePickerTarget?>(null) }
    var activeRoomTimePicker by remember { mutableStateOf<RoomSchedulePickerTarget?>(null) }
    val roomInputFocusRequester = remember { FocusRequester() }
    val roomListState = rememberLazyListState()
    var roomInputHasFocus by remember { mutableStateOf(false) }
    var draggingRoomId by remember { mutableStateOf<String?>(null) }
    var pendingVisibleRoomId by remember { mutableStateOf<String?>(null) }

    val runtimeStates by RuntimeStateStore.roomStates.collectAsState()
    val monitorRunning by RuntimeStateStore.monitorRunning.collectAsState()
    val intervalStatusText = when (intervalSaveState) {
        IntervalFieldState.IDLE -> if (isZh) "\u81ea\u52a8\u4fdd\u5b58" else "Auto-save"
        IntervalFieldState.SAVING -> if (isZh) "\u4fdd\u5b58\u4e2d..." else "Saving..."
        IntervalFieldState.SAVED -> if (isZh) "\u5df2\u4fdd\u5b58 ${settings.checkIntervalSeconds} \u79d2" else "Saved: ${settings.checkIntervalSeconds}s"
        IntervalFieldState.INVALID -> if (isZh) "\u8303\u56f4 10-600 \u79d2" else "Use 10-600s"
    }
    val intervalStatusColor = when (intervalSaveState) {
        IntervalFieldState.SAVED -> MaterialTheme.colorScheme.primary
        IntervalFieldState.INVALID -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    LaunchedEffect(runtimeStates) {
        rooms = AppPrefs.getRooms(context)
    }


    LaunchedEffect(roomInputHasFocus, roomInputValue.text) {
        if (!roomInputHasFocus || roomInputValue.text.isNotBlank()) {
            return@LaunchedEffect
        }
        delay(5_000L)
        if (roomInputHasFocus && roomInputValue.text.isBlank()) {
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(settings.checkIntervalSeconds) {
        if (intervalSaveState == IntervalFieldState.SAVED && intervalInput != settings.checkIntervalSeconds.toString()) {
            intervalInput = settings.checkIntervalSeconds.toString()
        }
    }

    LaunchedEffect(intervalInput) {
        val parsed = intervalInput.toIntOrNull()
        when {
            intervalInput.isBlank() -> {
                intervalSaveState = IntervalFieldState.IDLE
                return@LaunchedEffect
            }
            parsed == null -> {
                intervalSaveState = IntervalFieldState.INVALID
                return@LaunchedEffect
            }
            parsed == settings.checkIntervalSeconds -> {
                intervalSaveState = IntervalFieldState.SAVED
                return@LaunchedEffect
            }
            else -> intervalSaveState = IntervalFieldState.SAVING
        }

        delay(450L)
        val latestParsed = intervalInput.toIntOrNull() ?: return@LaunchedEffect
        val updatedInterval = latestParsed.coerceIn(
            AppPrefs.MIN_CHECK_INTERVAL_SECONDS,
            AppPrefs.MAX_CHECK_INTERVAL_SECONDS
        )
        val updated = settings.copy(checkIntervalSeconds = updatedInterval)
        settings = updated
        intervalInput = updatedInterval.toString()
        AppPrefs.setSettings(context, updated)
        intervalSaveState = IntervalFieldState.SAVED
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }

        val updated = settings.copy(
            storageMode = StorageMode.DOCUMENT_TREE,
            storageTreeUri = uri.toString()
        )
        settings = updated
        AppPrefs.setSettings(context, updated)
        Toast.makeText(context, AppText.storageFolderSelected(context), Toast.LENGTH_SHORT).show()
    }

    fun triggerImmediateMonitorIfRunning() {
        if (monitorRunning) {
            LiveMonitorService.triggerNow(context)
        }
    }

    suspend fun keepDraggedRoomVisible(targetIndex: Int) {
        val layoutInfo = roomListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) {
            return
        }

        val firstVisibleIndex = visibleItems.first().index
        val firstVisibleOffset = roomListState.firstVisibleItemScrollOffset
        val lastVisibleIndex = visibleItems.last().index
        val visibleCount = visibleItems.size.coerceAtLeast(1)
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset
        val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
        when {
            targetIndex == 0 &&
                (roomListState.firstVisibleItemIndex > 0 || firstVisibleOffset > 0 || targetItem == null || targetItem.offset > viewportStart) -> {
                roomListState.scrollToItem(0)
            }

            targetIndex < firstVisibleIndex -> {
                roomListState.scrollToItem(targetIndex)
            }

            targetItem != null && targetItem.offset < viewportStart -> {
                roomListState.scrollToItem(targetIndex)
            }

            targetItem != null && targetItem.offset + targetItem.size > viewportEnd -> {
                val anchorIndex = (targetIndex - visibleCount + 1).coerceAtLeast(0)
                roomListState.scrollToItem(anchorIndex)
            }

            targetIndex > lastVisibleIndex -> {
                val anchorIndex = (targetIndex - visibleCount + 1).coerceAtLeast(0)
                roomListState.scrollToItem(anchorIndex)
            }
        }
    }

    LaunchedEffect(rooms, pendingVisibleRoomId) {
        val roomId = pendingVisibleRoomId ?: return@LaunchedEffect
        val targetIndex = rooms.indexOfFirst { it.id == roomId }
        if (targetIndex >= 0) {
            keepDraggedRoomVisible(targetIndex)
        }
        pendingVisibleRoomId = null
    }

    fun addRoomFromInput() {
        val result = AppPrefs.addRoom(context, roomInputValue.text)
        if (result.error != null) {
            Toast.makeText(context, result.error, Toast.LENGTH_SHORT).show()
            roomInputValue = roomInputValue.copy(selection = TextRange(roomInputValue.text.length))
            return
        }

        roomInputValue = TextFieldValue("")
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        rooms = AppPrefs.getRooms(context)
        RuntimeStateStore.removeMissing(rooms.map { it.id }.toSet())

        val newRoom = result.room
        if (newRoom != null) {
            triggerImmediateMonitorIfRunning()
            scope.launch {
                runCatching { resolver.probe(newRoom.normalizedInput) }
                    .onSuccess { probe ->
                        AppPrefs.updateRoomMetadata(
                            context = context,
                            roomId = newRoom.id,
                            displayName = probe.roomDisplayName,
                            douyinId = probe.douyinId,
                            avatarUrl = probe.avatarUrl,
                            resolvedRoomId = probe.resolvedRoomId
                        )
                    }
                rooms = AppPrefs.getRooms(context)
            }
        }
    }

    fun startMonitorNow() {
        if (settings.storageMode == StorageMode.DOCUMENT_TREE && settings.storageTreeUri.isNullOrBlank()) {
            Toast.makeText(
                context,
                AppText.selectStorageFolderFirst(context),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val enabledRooms = AppPrefs.getRooms(context).count { it.enabled }
        if (enabledRooms == 0) {
            Toast.makeText(
                context,
                AppText.noEnabledRooms(context),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val updated = settings.copy(monitorEnabled = true)
        settings = updated
        AppPrefs.setSettings(context, updated)

        val now = System.currentTimeMillis()
        AppPrefs.getRooms(context).filter { it.enabled }.forEach { room ->
            RuntimeStateStore.updateRoom(room.id) {
                it.copy(
                    status = RoomStatus.CHECKING,
                    message = AppText.startingMonitor(context),
                    lastCheckedAtMs = now,
                    saveProgressPercent = null
                )
            }
        }

        LiveMonitorService.start(context)
        RuntimeStateStore.setMonitorRunning(true)
    }

    activeTimePicker?.let { pickerTarget ->
        val initialMinutes = when (pickerTarget) {
            SchedulePickerTarget.START -> MonitorWindow.parseMinutes(windowStartInput)
                ?: settings.monitorWindowStartMinutes
            SchedulePickerTarget.END -> MonitorWindow.parseMinutes(windowEndInput)
                ?: settings.monitorWindowEndMinutes
        }
        WheelTimePickerDialog(
            title = when (pickerTarget) {
                SchedulePickerTarget.START -> AppText.scheduleStartLabel(context)
                SchedulePickerTarget.END -> AppText.scheduleEndLabel(context)
            },
            initialMinutes = initialMinutes,
            onDismiss = { activeTimePicker = null },
            onConfirm = { pickedMinutes ->
                when (pickerTarget) {
                    SchedulePickerTarget.START -> {
                        windowStartInput = MonitorWindow.formatMinutes(pickedMinutes)
                    }
                    SchedulePickerTarget.END -> {
                        windowEndInput = MonitorWindow.formatMinutes(pickedMinutes)
                    }
                }
                activeTimePicker = null
            }
        )
    }

    activeRoomTimePicker?.let { picker ->
        val targetRoom = rooms.firstOrNull { it.id == picker.roomId }
        if (targetRoom == null) {
            activeRoomTimePicker = null
        } else {
            val initialMinutes = when (picker.target) {
                SchedulePickerTarget.START -> targetRoom.monitorWindowStartMinutes
                SchedulePickerTarget.END -> targetRoom.monitorWindowEndMinutes
            }
            WheelTimePickerDialog(
                title = when (picker.target) {
                    SchedulePickerTarget.START -> AppText.scheduleStartLabel(context)
                    SchedulePickerTarget.END -> AppText.scheduleEndLabel(context)
                },
                initialMinutes = initialMinutes,
                onDismiss = { activeRoomTimePicker = null },
                onConfirm = { pickedMinutes ->
                    val updatedStart = if (picker.target == SchedulePickerTarget.START) {
                        pickedMinutes
                    } else {
                        targetRoom.monitorWindowStartMinutes
                    }
                    val updatedEnd = if (picker.target == SchedulePickerTarget.END) {
                        pickedMinutes
                    } else {
                        targetRoom.monitorWindowEndMinutes
                    }
                    AppPrefs.setRoomSchedule(
                        context = context,
                        roomId = targetRoom.id,
                        enabled = targetRoom.scheduleEnabled,
                        startMinutes = updatedStart,
                        endMinutes = updatedEnd
                    )
                    rooms = AppPrefs.getRooms(context)
                    triggerImmediateMonitorIfRunning()
                    activeRoomTimePicker = null
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AppText.appTitle(context),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            MonitorStatusBadge(isRunning = monitorRunning)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val targetEnabled = !monitorRunning
                    if (targetEnabled) {
                        startMonitorNow()
                    } else {
                        val updated = settings.copy(monitorEnabled = false)
                        settings = updated
                        AppPrefs.setSettings(context, updated)
                        LiveMonitorService.stop(context)
                        RuntimeStateStore.setMonitorRunning(false)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (monitorRunning) AppText.stopMonitoring(context) else AppText.startMonitoring(context))
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(AppText.roomsTab(context)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(AppText.settingsTab(context)) }
            )
        }

        when (selectedTab) {
            0 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = roomInputValue,
                            onValueChange = { roomInputValue = it },
                            label = { Text(AppText.roomHint(context)) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(roomInputFocusRequester)
                                .onFocusChanged { roomInputHasFocus = it.isFocused },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { addRoomFromInput() }
                            )
                        )

                        Button(onClick = { addRoomFromInput() }) {
                            Text(AppText.addRoomShort(context))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = AppText.monitoredRooms(context, rooms.size))
                        DragSortBadge()
                    }

                    Text(
                        text = AppText.dragSortHint(context),
                        style = MaterialTheme.typography.bodySmall
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        state = roomListState,
                        contentPadding = PaddingValues(bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = rooms,
                            key = { _, room -> room.id }
                        ) { index, room ->
                            val runRoomCheck: () -> Unit = {
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    RuntimeStateStore.updateRoom(room.id) {
                                        it.copy(
                                            status = RoomStatus.CHECKING,
                                            message = AppText.checking(context),
                                            lastCheckedAtMs = now,
                                            saveProgressPercent = null
                                        )
                                    }

                                    val probe = resolver.probe(room.normalizedInput)
                                    AppPrefs.updateRoomMetadata(
                                        context = context,
                                        roomId = room.id,
                                        displayName = probe.roomDisplayName,
                                        douyinId = probe.douyinId,
                                        avatarUrl = probe.avatarUrl,
                                        resolvedRoomId = probe.resolvedRoomId
                                    )
                                    rooms = AppPrefs.getRooms(context)

                                    val hasStream = selectPreviewStreamUrl(probe.streamUrls, settings.quality, settings.bitrate) != null
                                    val showOfflineStatus = probe.shouldShowOfflineStatus()
                                    val message = when {
                                        probe.blockedByVerification -> AppText.verificationRequired(context)
                                        showOfflineStatus -> AppText.offline(context)
                                        !probe.isLive && !probe.isReliable -> probe.message ?: AppText.probeUncertain(context)
                                        !probe.isLive -> AppText.offline(context)
                                        !hasStream -> AppText.liveNoStream(context)
                                        else -> AppText.roomReady(context)
                                    }
                                    val status = when {
                                        probe.blockedByVerification -> RoomStatus.ERROR
                                        showOfflineStatus -> RoomStatus.OFFLINE
                                        !probe.isLive && !probe.isReliable -> RoomStatus.ERROR
                                        !probe.isLive -> RoomStatus.OFFLINE
                                        !hasStream -> RoomStatus.ERROR
                                        else -> RoomStatus.LIVE
                                    }

                                    val checkedAt = System.currentTimeMillis()
                                    RuntimeStateStore.updateRoom(room.id) { current ->
                                        val wasLive = current.status == RoomStatus.LIVE || current.status == RoomStatus.RECORDING
                                        val liveStartAt = if (probe.isLive && !wasLive) checkedAt else current.lastLiveStartAtMs
                                        val liveEndAt = if (status == RoomStatus.OFFLINE && wasLive) checkedAt else current.lastLiveEndAtMs
                                        current.copy(
                                            status = status,
                                            message = message,
                                            lastCheckedAtMs = checkedAt,
                                            saveProgressPercent = null,
                                            lastLiveStartAtMs = liveStartAt,
                                            lastLiveEndAtMs = liveEndAt
                                        )
                                    }
                                }
                            }
                            RoomCard(
                                room = room,
                                runtime = runtimeStates[room.id],
                                index = index,
                                lastIndex = rooms.lastIndex,
                                onDragStateChange = { isDragging ->
                                    draggingRoomId = if (isDragging) room.id else draggingRoomId.takeUnless { it == room.id }
                                },
                                onMoveTo = { targetIndex ->
                                    val moved = AppPrefs.moveRoomTo(context, room.id, targetIndex)
                                    if (moved) {
                                        rooms = AppPrefs.getRooms(context)
                                        pendingVisibleRoomId = room.id
                                    }
                                    moved
                                },
                                onToggleEnabled = { enabled ->
                                    AppPrefs.setRoomEnabled(context, room.id, enabled)
                                    rooms = AppPrefs.getRooms(context)
                                    triggerImmediateMonitorIfRunning()
                                },
                                onScheduleEnabledChange = { enabled ->
                                    AppPrefs.setRoomSchedule(
                                        context = context,
                                        roomId = room.id,
                                        enabled = enabled,
                                        startMinutes = room.monitorWindowStartMinutes,
                                        endMinutes = room.monitorWindowEndMinutes
                                    )
                                    rooms = AppPrefs.getRooms(context)
                                    triggerImmediateMonitorIfRunning()
                                },
                                onScheduleStartClick = {
                                    activeRoomTimePicker = RoomSchedulePickerTarget(room.id, SchedulePickerTarget.START)
                                },
                                onScheduleEndClick = {
                                    activeRoomTimePicker = RoomSchedulePickerTarget(room.id, SchedulePickerTarget.END)
                                },
                                onCheck = runRoomCheck,
                                onDelete = {
                                    AppPrefs.removeRoom(context, room.id)
                                    val latest = AppPrefs.getRooms(context)
                                    RuntimeStateStore.removeMissing(latest.map { it.id }.toSet())
                                    rooms = latest
                                    triggerImmediateMonitorIfRunning()
                                }
                            )
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = AppText.checkIntervalLabel(context))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = intervalInput,
                            onValueChange = {
                                intervalInput = it.filter(Char::isDigit).take(3)
                                intervalSaveState = IntervalFieldState.SAVING
                            },
                            modifier = Modifier
                                .width(112.dp)
                                .height(50.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = { Text("45") }
                        )
                        Text(
                            text = intervalStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = intervalStatusColor
                        )
                    }

                    Text(text = AppText.scheduleLabel(context))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = AppText.scheduleEnabled(context))
                        Switch(
                            checked = settings.scheduleEnabled,
                            onCheckedChange = { enabled ->
                                settings = settings.copy(scheduleEnabled = enabled)
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScheduleTimeField(
                            label = AppText.scheduleStartLabel(context),
                            value = windowStartInput,
                            modifier = Modifier.weight(1f),
                            onClick = { activeTimePicker = SchedulePickerTarget.START }
                        )
                        ScheduleTimeField(
                            label = AppText.scheduleEndLabel(context),
                            value = windowEndInput,
                            modifier = Modifier.weight(1f),
                            onClick = { activeTimePicker = SchedulePickerTarget.END }
                        )
                    }

                    Text(
                        text = AppText.scheduleHint(context),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(text = AppText.qualityLabel(context))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RecordQuality.entries.forEach { quality ->
                            FilterChip(
                                selected = settings.quality == quality,
                                onClick = {
                                    val updated = settings.copy(quality = quality)
                                    settings = updated
                                    AppPrefs.setSettings(context, updated)
                                },
                                label = { Text(quality.label) }
                            )
                        }
                    }

                    Text(text = AppText.bitrateLabel(context))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BitratePreset.entries.forEach { bitrate ->
                            FilterChip(
                                selected = settings.bitrate == bitrate,
                                onClick = {
                                    val updated = settings.copy(bitrate = bitrate)
                                    settings = updated
                                    AppPrefs.setSettings(context, updated)
                                },
                                label = { Text(bitrate.label) }
                            )
                        }
                    }

                    Text(text = AppText.storageLabel(context))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StorageMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.storageMode == mode,
                                onClick = {
                                    val updated = settings.copy(storageMode = mode)
                                    settings = updated
                                    AppPrefs.setSettings(context, updated)
                                },
                                label = { Text(AppText.storageMode(context, mode)) }
                            )
                        }
                    }

                    Text(text = AppText.outputFilesLabel(context))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.saveOutputMode == SaveOutputMode.RAW_ONLY,
                            onClick = {
                                val updated = settings.copy(saveOutputMode = SaveOutputMode.RAW_ONLY)
                                settings = updated
                                AppPrefs.setSettings(context, updated)
                            },
                            label = { Text(AppText.rawMp4OnlyLabel(context)) }
                        )
                        FilterChip(
                            selected = settings.saveOutputMode == SaveOutputMode.RAW_AND_DANMU,
                            onClick = {
                                val updated = settings.copy(saveOutputMode = SaveOutputMode.RAW_AND_DANMU)
                                settings = updated
                                AppPrefs.setSettings(context, updated)
                            },
                            label = { Text(AppText.rawAndDanmuLabel(context)) }
                        )
                    }

                    if (settings.storageMode == StorageMode.DOCUMENT_TREE) {
                        Button(
                            onClick = { treePicker.launch(settings.storageTreeUri?.let(Uri::parse)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppText.selectFolder(context))
                        }

                        Text(
                            text = settings.storageTreeUri ?: AppText.noFolderSelected(context),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            val parsedInterval = intervalInput.toIntOrNull()
                            if (parsedInterval == null) {
                                Toast.makeText(context, AppText.invalidInterval(context), Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val parsedStart = MonitorWindow.parseMinutes(windowStartInput)
                            val parsedEnd = MonitorWindow.parseMinutes(windowEndInput)
                            if (parsedStart == null || parsedEnd == null) {
                                Toast.makeText(context, AppText.invalidTime(context), Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val updated = settings.copy(
                                checkIntervalSeconds = parsedInterval.coerceIn(
                                    AppPrefs.MIN_CHECK_INTERVAL_SECONDS,
                                    AppPrefs.MAX_CHECK_INTERVAL_SECONDS
                                ),
                                monitorWindowStartMinutes = parsedStart,
                                monitorWindowEndMinutes = parsedEnd
                            )
                            settings = updated
                            intervalInput = updated.checkIntervalSeconds.toString()
                            windowStartInput = MonitorWindow.formatMinutes(updated.monitorWindowStartMinutes)
                            windowEndInput = MonitorWindow.formatMinutes(updated.monitorWindowEndMinutes)
                            AppPrefs.setSettings(context, updated)
                            keyboardController?.hide()
                            focusManager.clearFocus(force = true)
                            Toast.makeText(context, AppText.settingsSaved(context), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(AppText.saveSettings(context))
                    }
                }
            }
        }
    }
}
private enum class IntervalFieldState {
    IDLE,
    SAVING,
    SAVED,
    INVALID
}

private enum class SchedulePickerTarget {
    START,
    END
}

private data class RoomSchedulePickerTarget(
    val roomId: String,
    val target: SchedulePickerTarget
)
@Composable
private fun ScheduleTimeField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = value)
        }
    }
}

@Composable
private fun WheelTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val context = LocalContext.current
    var hour by remember(initialMinutes) { mutableStateOf((initialMinutes / 60).coerceIn(0, 23)) }
    var minute by remember(initialMinutes) { mutableStateOf((initialMinutes % 60).coerceIn(0, 59)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelNumberPicker(
                    value = hour,
                    range = 0..23,
                    formatter = { String.format("%02d", it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp),
                    onValueChange = { hour = it }
                )
                Text(text = ":", style = MaterialTheme.typography.titleLarge)
                WheelNumberPicker(
                    value = minute,
                    range = 0..59,
                    formatter = { String.format("%02d", it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp),
                    onValueChange = { minute = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                Text(text = context.getString(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = context.getString(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun WheelNumberPicker(
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String,
    onValueChange: (Int) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            NumberPicker(context).apply {
                minValue = range.first
                maxValue = range.last
                wrapSelectorWheel = true
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setFormatter { formatter(it) }
                this.value = value
                setOnValueChangedListener { _, _, newValue ->
                    onValueChange(newValue)
                }
            }
        },
        update = { picker ->
            picker.minValue = range.first
            picker.maxValue = range.last
            picker.wrapSelectorWheel = true
            picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            picker.setFormatter { formatter(it) }
            if (picker.value != value) {
                picker.value = value
            }
            picker.setOnValueChangedListener { _, _, newValue ->
                onValueChange(newValue)
            }
        }
    )
}

@Composable
private fun MonitorStatusBadge(isRunning: Boolean) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (isRunning) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (isRunning) AppText.running(context) else AppText.stopped(context),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DragSortBadge() {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = AppText.dragSortLabel(context),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun calculateDragTargetOffset(totalDragPx: Float, stepPx: Float, thresholdPx: Float): Int {
    if (stepPx <= 0f) {
        return 0
    }

    return when {
        totalDragPx >= thresholdPx -> ((totalDragPx - thresholdPx) / stepPx).toInt() + 1
        totalDragPx <= -thresholdPx -> -(((-totalDragPx - thresholdPx) / stepPx).toInt() + 1)
        else -> 0
    }
}

private fun constrainDraggedCardOffset(offsetPx: Float, currentIndex: Int, lastIndex: Int): Float {
    return when {
        currentIndex <= 0 -> 0f
        currentIndex >= lastIndex -> 0f
        else -> offsetPx
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomCard(
    modifier: Modifier = Modifier,
    room: MonitoredRoom,
    runtime: RoomRuntimeState?,
    index: Int,
    lastIndex: Int,
    onDragStateChange: (Boolean) -> Unit,
    onMoveTo: (Int) -> Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onScheduleStartClick: () -> Unit,
    onScheduleEndClick: () -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val isZh = context.resources.configuration.locales.let { it.size() > 0 && it[0].language.startsWith("zh") }
    val nowMs = rememberTickerNow()

    val roomIdFallback = RoomInputNormalizer.extractLiveRoomId(room.normalizedInput)
    val displayId = room.douyinId ?: RoomInputNormalizer.extractIdHint(room.input) ?: roomIdFallback ?: room.input
    val displayTitle = room.displayName ?: RoomInputNormalizer.extractDisplayHint(room.input) ?: displayId
    val saveProgressPercent = runtime?.saveProgressPercent
    val statusLabel = if (saveProgressPercent != null) {
        if (isZh) "\u4fdd\u5b58\u4e2d" else "Saving"
    } else {
        AppText.statusLabel(context, runtime?.status ?: RoomStatus.OFFLINE)
    }
    val recordingElapsed = if (runtime?.status == RoomStatus.RECORDING && runtime.startedAtMs != null) {
        formatElapsedDuration(nowMs - runtime.startedAtMs)
    } else {
        null
    }
    val messageText = runtime?.message?.takeIf { it.isNotBlank() }
    val baseStatusLine = if (recordingElapsed != null) {
        if (isZh) {
            "\u72b6\u6001: $statusLabel  |  \u65f6\u957f $recordingElapsed"
        } else {
            "Status: $statusLabel  |  $recordingElapsed"
        }
    } else {
        if (isZh) "\u72b6\u6001: $statusLabel" else "Status: $statusLabel"
    }
    val statusLine = if (messageText != null) {
        "$baseStatusLine  |  $messageText"
    } else {
        baseStatusLine
    }
    val roomWindowStart = MonitorWindow.formatMinutes(room.monitorWindowStartMinutes)
    val roomWindowEnd = MonitorWindow.formatMinutes(room.monitorWindowEndMinutes)
    var dragOffsetPx by remember(room.id) { mutableStateOf(0f) }
    var isDragging by remember(room.id) { mutableStateOf(false) }
    var cardHeightPx by remember(room.id) { mutableStateOf(0) }
    var totalDragPx by remember(room.id) { mutableStateOf(0f) }
    var dragStartIndex by remember(room.id) { mutableStateOf(index) }
    var currentIndex by remember(room.id) { mutableStateOf(index) }
    val itemStepPx = with(LocalDensity.current) {
        if (cardHeightPx > 0) cardHeightPx.toFloat() + 6.dp.toPx() else 84.dp.toPx()
    }
    val minimumThresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
    val latestIndex = rememberUpdatedState(index)
    val latestLastIndex = rememberUpdatedState(lastIndex)
    val latestOnMoveTo = rememberUpdatedState(onMoveTo)
    val latestOnDragStateChange = rememberUpdatedState(onDragStateChange)
    val latestItemStepPx = rememberUpdatedState(itemStepPx)
    val latestThresholdPx = rememberUpdatedState(minimumThresholdPx)

    LaunchedEffect(index, isDragging) {
        if (!isDragging) {
            dragStartIndex = index
            currentIndex = index
            totalDragPx = 0f
            dragOffsetPx = 0f
        } else {
            currentIndex = index
        }
    }

    fun finishDrag() {
        totalDragPx = 0f
        dragOffsetPx = 0f
        dragStartIndex = latestIndex.value
        currentIndex = latestIndex.value
        isDragging = false
        latestOnDragStateChange.value(false)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardHeightPx = it.height }
            .pointerInput(room.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        totalDragPx = 0f
                        dragOffsetPx = 0f
                        dragStartIndex = latestIndex.value
                        currentIndex = latestIndex.value
                        isDragging = true
                        latestOnDragStateChange.value(true)
                    },
                    onDragEnd = {
                        finishDrag()
                    },
                    onDragCancel = {
                        finishDrag()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragPx += dragAmount.y

                        val stepPx = latestItemStepPx.value
                        val reorderThresholdPx = max(latestThresholdPx.value, stepPx * 0.28f)
                        val targetOffset = calculateDragTargetOffset(totalDragPx, stepPx, reorderThresholdPx)
                        val targetIndex = (dragStartIndex + targetOffset).coerceIn(0, latestLastIndex.value)
                        if (targetIndex != currentIndex && latestOnMoveTo.value(targetIndex)) {
                            currentIndex = targetIndex
                        }

                        val relativeOffsetPx = totalDragPx - ((currentIndex - dragStartIndex) * stepPx)
                        dragOffsetPx = constrainDraggedCardOffset(
                            offsetPx = relativeOffsetPx,
                            currentIndex = currentIndex,
                            lastIndex = latestLastIndex.value
                        )
                    }
                )
            }
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetPx
                scaleX = if (isDragging) 1.01f else 1f
                scaleY = if (isDragging) 1.01f else 1f
            },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 10.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoomAvatar(
                    avatarUrl = room.avatarUrl,
                    fallbackText = displayTitle
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = displayId,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DragSortBadge()
            }

            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodyMedium
            )

            if (saveProgressPercent != null) {
                LinearProgressIndicator(
                    progress = { saveProgressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (isZh) "\u4fdd\u5b58\u8fdb\u5ea6: ${saveProgressPercent}%" else "Save progress: ${saveProgressPercent}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!runtime?.recordingPath.isNullOrBlank()) {
                Text(
                    text = runtime?.recordingPath.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if ((runtime?.lastCheckedAtMs ?: 0L) > 0L) {
                val checkTime = DateFormat.format("HH:mm:ss", runtime?.lastCheckedAtMs ?: 0L)
                Text(
                    text = AppText.lastCheck(context, checkTime),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = AppText.roomScheduleEnabled(context))
                Switch(
                    checked = room.scheduleEnabled,
                    onCheckedChange = onScheduleEnabledChange
                )
            }
            if (room.scheduleEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleTimeField(
                        label = AppText.scheduleStartLabel(context),
                        value = roomWindowStart,
                        modifier = Modifier.weight(1f),
                        onClick = onScheduleStartClick
                    )
                    ScheduleTimeField(
                        label = AppText.scheduleEndLabel(context),
                        value = roomWindowEnd,
                        modifier = Modifier.weight(1f),
                        onClick = onScheduleEndClick
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = room.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                    Text(text = if (room.enabled) AppText.enabled(context) else AppText.disabled(context))
                }

                OutlinedButton(onClick = onCheck) {
                    Text(AppText.check(context))
                }

                OutlinedButton(onClick = onDelete) {
                    Text(AppText.delete(context))
                }
            }
        }
    }
}

@Composable
private fun RoomAvatar(
    avatarUrl: String?,
    fallbackText: String
) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = "Room avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        )
        return
    }

    val char = fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(text = char, style = MaterialTheme.typography.titleMedium)
    }
}

private fun selectPreviewStreamUrl(
    streamUrls: Map<String, String>,
    quality: RecordQuality,
    bitrate: BitratePreset
): String? {
    return StreamSelector.primaryUrl(streamUrls, quality, bitrate)
}

@Composable
private fun rememberTickerNow(): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return nowMs
}

private fun formatElapsedDuration(durationMs: Long): String {
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

private fun formatAbsoluteTime(timeMs: Long): String {
    return DateFormat.format("MM-dd HH:mm", timeMs).toString()
}














































