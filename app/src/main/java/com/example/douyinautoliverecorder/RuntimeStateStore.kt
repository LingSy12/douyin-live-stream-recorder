package com.example.douyinautoliverecorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RuntimeStateStore {
    private val _roomStates = MutableStateFlow<Map<String, RoomRuntimeState>>(emptyMap())
    val roomStates = _roomStates.asStateFlow()

    private val _monitorRunning = MutableStateFlow(false)
    val monitorRunning = _monitorRunning.asStateFlow()

    fun setMonitorRunning(running: Boolean) {
        _monitorRunning.value = running
    }

    fun updateRoom(roomId: String, reducer: (RoomRuntimeState) -> RoomRuntimeState) {
        _roomStates.update { current ->
            val existing = current[roomId] ?: RoomRuntimeState()
            current + (roomId to reducer(existing))
        }
    }

    fun reconcileRecordingStates(activeRoomIds: Set<String>) {
        _roomStates.update { current ->
            current.mapValues { (roomId, state) ->
                if (state.status == RoomStatus.RECORDING && roomId !in activeRoomIds) {
                    state.copy(
                        status = RoomStatus.IDLE,
                        message = "",
                        startedAtMs = null,
                        saveProgressPercent = null
                    )
                } else {
                    state
                }
            }
        }
    }

    fun removeMissing(validRoomIds: Set<String>) {
        _roomStates.update { current ->
            current.filterKeys { it in validRoomIds }
        }
    }

    fun clearAll() {
        _roomStates.value = emptyMap()
    }
}

