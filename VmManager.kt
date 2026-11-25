package com.vantora.app.vm

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import com.vantora.app.core.AppDb
import com.vantora.app.core.GuestArch
import com.vantora.app.core.VmEntity
import com.vantora.app.device.Advice
import com.vantora.app.device.DeviceCaps
import com.vantora.app.device.NativeBridge
import com.vantora.app.device.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.RandomAccessFile

class VmManager(private val ctx: Context, private val db: AppDb) {
    private val dao = db.vmDao()

    /** VM data goes to the volume with more free space (app-specific external storage vs internal). */
    private fun storageBase(): File {
        val ext = ctx.getExternalFilesDir(null)
        fun free(f: File) = try { StatFs(f.path).availableBytes } catch (t: Throwable) { 0L }
        val base = if (ext != null && free(ext) >= free(ctx.filesDir)) ext else ctx.filesDir
        return File(base, "vms").apply { mkdirs() }
    }

    fun vmDir(vm: VmEntity): File = File(vm.diskPath).parentFile ?: storageBase()

    fun engineBinary(arch: GuestArch): File? {
        val name = if (arch == GuestArch.ARM64) "libqemu-system-aarch64.so" else "libqemu-system-x86_64.so"
        val f = File(ctx.applicationInfo.nativeLibraryDir, name)
        return f.takeIf { it.exists() }
    }

    fun firmwareAvailable(arch: GuestArch): Boolean {
        val names = if (arch == GuestArch.ARM64) listOf("QEMU_EFI.fd") else listOf("OVMF_CODE.fd", "OVMF_VARS.fd")
        return names.all { n -> try { ctx.assets.open("firmware/$n").use { true } } catch (e: FileNotFoundException) { false } }
    }

    fun engineStatus(caps: DeviceCaps?): List<Advice> {
        val out = mutableListOf<Advice>()
        out += Advice(if (NativeBridge.available) Severity.INFO else Severity.BLOCKER,
            if (NativeBridge.available) "Native helper library loaded." else "Native helper library (libvantora_native.so) failed to load.")
        for (arch in listOf(GuestArch.ARM64, GuestArch.X64)) {
            val bin = engineBinary(arch)
            out += Advice(if (bin != null) Severity.INFO else Severity.WARNING,
                if (bin != null) "QEMU engine for $arch: bundled (${bin.length() / 1024 / 1024} MB)." else "QEMU engine for $arch: NOT bundled in this APK.")
            out += Advice(if (firmwareAvailable(arch)) Severity.INFO else Severity.WARNING,
                if (firmwareAvailable(arch)) "UEFI firmware for $arch: present." else "UEFI firmware for $arch: missing.")
        }
        if (Build.VERSION.SDK_INT < 28) out += Advice(Severity.BLOCKER, "Engine requires Android 9+.")
        if (caps != null && !caps.kvmAccessible) out += Advice(Severity.WARNING, "/dev/kvm not accessible: software emulation only.")
        return out
    }

    suspend fun create(
        name: String, arch: GuestArch, ramMb: Int, cores: Int, diskGb: Int,
        width: Int, height: Int, network: Boolean, isoEntryId: Long?
    ): VmEntity = withContext(Dispatchers.IO) {
        val draft = VmEntity(name = name, guestArch = arch.name, ramMb = ramMb, cores = cores, diskGb = diskGb,
            width = width, height = height, network = network, isoEntryId = isoEntryId,
            diskPath = "", createdAt = System.currentTimeMillis())
        val id = dao.insert(draft)
        val dir = File(storageBase(), id.toString()).apply { mkdirs() }
        val disk = File(dir, "disk.raw")
        // Sparse raw disk: logical size is reserved, blocks are allocated as Windows writes them.
        RandomAccessFile(disk, "rw").use { it.setLength(diskGb * 1024L * 1024L * 1024L) }
        val vm = draft.copy(id = id, diskPath = disk.path)
        dao.update(vm)
        vm
    }

    /** RAM/CPU/network/resolution can change freely; the virtual disk can only grow. */
    suspend fun updateConfig(old: VmEntity, new: VmEntity) = withContext(Dispatchers.IO) {
        if (new.diskGb > old.diskGb) {
            RandomAccessFile(File(old.diskPath), "rw").use { it.setLength(new.diskGb * 1024L * 1024L * 1024L) }
        }
        dao.update(new.copy(diskGb = maxOf(old.diskGb, new.diskGb)))
    }

    suspend fun detachMedia(vm: VmEntity) = withContext(Dispatchers.IO) { dao.update(vm.copy(isoEntryId = null)) }

    /** Deletes ONLY the VM's own folder (disk, firmware vars, log). Library files are never touched. */
    suspend fun delete(vm: VmEntity) = withContext(Dispatchers.IO) {
        File(vm.diskPath).parentFile?.deleteRecursively()
        dao.delete(vm)
    }

    private fun copyAsset(asset: String, dest: File) {
        try {
            ctx.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("UEFI firmware asset '$asset' is missing from this APK. The GitHub build fetches it via scripts/fetch-firmware.sh.")
        }
    }

    /** Returns (code, vars) pflash paths for the VM, creating them on first start. */
    fun prepareFirmware(vm: VmEntity, arch: GuestArch): Pair<String, String> {
        val dir = File(vm.diskPath).parentFile ?: error("VM directory missing")
        val code = File(dir, "efi-code.fd")
        val vars = File(dir, "efi-vars.fd")
        if (arch == GuestArch.ARM64) {
            if (!code.exists()) {
                copyAsset("firmware/QEMU_EFI.fd", code)
                RandomAccessFile(code, "rw").use { it.setLength(64L shl 20) }   // virt pflash must be 64 MiB
            }
            if (!vars.exists()) RandomAccessFile(vars, "rw").use { it.setLength(64L shl 20) }
        } else {
            if (!code.exists()) copyAsset("firmware/OVMF_CODE.fd", code)
            if (!vars.exists()) copyAsset("firmware/OVMF_VARS.fd", vars)
        }
        return code.path to vars.path
    }

    /** Fallback when the install media is not seekable (e.g. cloud provider): copy into VM storage. */
    fun stageMedia(pfd: ParcelFileDescriptor, vm: VmEntity): String {
        val dir = File(vm.diskPath).parentFile ?: error("VM directory missing")
        val dest = File(dir, "media.iso")
        if (!dest.exists()) FileInputStream(pfd.fileDescriptor).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        return dest.path
    }
}
