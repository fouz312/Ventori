package com.vantora.app.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vantora.app.R
import com.vantora.app.core.GuestArch
import com.vantora.app.core.ServiceLocator
import com.vantora.app.device.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Keeps the QEMU child process alive in the foreground and reports its state through [VmRuntime]. */
class VmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("vm", "Virtual computer", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "vm")
            .setSmallIcon(R.drawable.ic_logo_foreground)
            .setContentTitle("Virtual computer running")
            .setContentText("Tap the app to open the display.")
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

        val id = intent?.getLongExtra("vmId", -1L) ?: -1L
        if (id < 0 || VmRuntime.isBusy) { stopSelfResult(startId); return START_NOT_STICKY }
        scope.launch { runVm(id); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    private suspend fun runVm(id: Long) {
        val sl = ServiceLocator
        VmRuntime.state.value = VmState.Starting
        var pfd: ParcelFileDescriptor? = null
        try {
            val vm = sl.db.vmDao().get(id) ?: error("Virtual computer not found")
            val arch = if (vm.guestArch == "ARM64") GuestArch.ARM64 else GuestArch.X64
            val bin = sl.vmManager.engineBinary(arch)
                ?: error("The QEMU engine for $arch is not bundled in this APK. Run the 'qemu-engine' job of the GitHub workflow (docs/VIRTUALIZATION.md).")
            val (fwCode, fwVars) = sl.vmManager.prepareFirmware(vm, arch)
            val dir = File(vm.diskPath).parentFile!!

            val keep = mutableListOf<Int>()
            var isoPath: String? = null
            val entryId = vm.isoEntryId
            if (entryId != null) {
                val entry = sl.db.libraryDao().get(entryId)
                if (entry != null) {
                    val p = contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")
                        ?: error("Cannot open the install media. Open Files and reconnect it.")
                    pfd = p
                    if (NativeBridge.isSeekable(p.fd)) {
                        keep += p.fd
                        isoPath = "/proc/self/fd/${p.fd}"    // inherited by the QEMU child, no copy needed
                    } else {
                        isoPath = sl.vmManager.stageMedia(p, vm)
                    }
                }
            }

            val run = File(filesDir, "run").apply { mkdirs() }
            val vnc = File(run, "vm-$id.vnc").also { it.delete() }
            val qmp = File(run, "vm-$id.qmp").also { it.delete() }
            val spec = QemuLaunchSpec(arch, vm.ramMb, vm.cores, vm.diskPath, isoPath, fwCode, fwVars,
                vnc.path, qmp.path, vm.network, kvm = false)
            val cmd = QemuCommand.build(bin.path, spec)
            val log = File(dir, "qemu.log")
            log.writeText("# " + cmd.joinToString(" ") + "\n")

            val pid = NativeBridge.spawn(cmd, dir.path, log.path, keep.toIntArray())
            pfd?.close(); pfd = null
            if (pid <= 0) error("Could not start the engine process (fork/exec failed: $pid).")

            VmRuntime.pid = pid; VmRuntime.qmpPath = qmp.path; VmRuntime.vmId = id
            sl.db.vmDao().update(vm.copy(lastStartedAt = System.currentTimeMillis()))
            VmRuntime.state.value = VmState.Running(id, pid, vnc.path)

            while (true) {
                val exit = NativeBridge.waitPid(pid, false)
                if (exit != NativeBridge.STILL_RUNNING) {
                    val tail = runCatching { log.readLines().takeLast(40).joinToString("\n") }.getOrDefault("")
                    VmRuntime.state.value = VmState.Stopped(exit, tail)
                    break
                }
                delay(500)
            }
        } catch (t: Throwable) {
            VmRuntime.state.value = VmState.Failed(t.message ?: t.toString())
        } finally {
            try { pfd?.close() } catch (_: Exception) { }
            VmRuntime.pid = 0
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
