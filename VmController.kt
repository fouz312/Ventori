package com.vantora.app.vm

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.vantora.app.core.VmEntity
import com.vantora.app.device.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object VmController {
    private val io = CoroutineScope(Dispatchers.IO)

    /** @return null on success, otherwise a message for the user. */
    fun start(ctx: Context, vm: VmEntity): String? {
        if (VmRuntime.isBusy && VmRuntime.vmId != vm.id) return "Another virtual computer is already running. Shut it down first."
        if (!VmRuntime.isBusy) {
            VmRuntime.state.value = VmState.Idle
            ContextCompat.startForegroundService(ctx, Intent(ctx, VmService::class.java).putExtra("vmId", vm.id))
        }
        ctx.startActivity(Intent(ctx, VmDisplayActivity::class.java).putExtra("vmId", vm.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    fun openDisplay(ctx: Context) {
        ctx.startActivity(Intent(ctx, VmDisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun qmp(cmd: String, onResult: (String) -> Unit = {}) {
        val p = VmRuntime.qmpPath ?: return onResult("No running virtual computer.")
        io.launch { onResult(runCatching { Qmp.command(p, cmd) }.getOrElse { "QMP failed: ${it.message}" }) }
    }

    /** ACPI power button: Windows shuts down cleanly. */
    fun shutDown(onResult: (String) -> Unit = {}) = qmp("system_powerdown", onResult)
    fun restart(onResult: (String) -> Unit = {}) = qmp("system_reset", onResult)

    /** Equivalent to pulling the plug: may corrupt the guest file system. */
    fun forceStop() { val pid = VmRuntime.pid; if (pid > 0) NativeBridge.kill(pid, 9) }
}
