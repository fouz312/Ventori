package com.vantora.app.vm

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface VmState {
    object Idle : VmState
    object Starting : VmState
    data class Running(val vmId: Long, val pid: Int, val vnc: String) : VmState
    data class Stopped(val exitCode: Int, val logTail: String) : VmState
    data class Failed(val message: String) : VmState
}

/** Process-wide state of the (single) running virtual machine. */
object VmRuntime {
    val state = MutableStateFlow<VmState>(VmState.Idle)
    @Volatile var pid: Int = 0
    @Volatile var qmpPath: String? = null
    @Volatile var vmId: Long = -1
    val isBusy get() = state.value is VmState.Starting || state.value is VmState.Running
}
