package com.vantora.app.device

import com.vantora.app.core.GuestArch

enum class Severity { INFO, WARNING, BLOCKER }
data class Advice(val severity: Severity, val text: String)

/** Decides what is realistically possible on this device. Pure logic (unit-tested). */
object Feasibility {
    fun maxRamMb(c: DeviceCaps): Int {
        val limit = minOf(c.totalRamMb * 0.5, c.availRamMb * 0.75)
        return (((limit / 256).toInt()) * 256).coerceAtLeast(1024)
    }

    fun maxCores(c: DeviceCaps): Int = (c.cpuCores - 1).coerceIn(1, 8)

    fun assess(c: DeviceCaps, arch: GuestArch, ramMb: Int, cores: Int, diskGb: Int, engineBundled: Boolean): List<Advice> {
        val out = mutableListOf<Advice>()
        if (!c.isArm64) out.add(Advice(Severity.BLOCKER,
            "This build bundles the VM engine for arm64-v8a only. Primary ABI here: ${c.abis.firstOrNull()}."))
        if (c.androidSdk < 28) out.add(Advice(Severity.BLOCKER,
            "The bundled QEMU engine needs Android 9 (API 28) or newer."))
        if (!engineBundled) out.add(Advice(Severity.BLOCKER,
            "The QEMU engine binary for $arch is not bundled in this APK. Run the 'qemu-engine' GitHub job (see docs/VIRTUALIZATION.md)."))
        if (c.kvmAccessible && arch == GuestArch.ARM64 && c.isArm64) {
            out.add(Advice(Severity.INFO, "/dev/kvm is accessible: hardware virtualization may be possible (experimental, not wired up in stage 1)."))
        } else {
            out.add(Advice(Severity.WARNING,
                "No hardware virtualization is available to normal Android apps (/dev/kvm is not accessible). " +
                    "Windows will run through QEMU software emulation (TCG): expect a very slow install (hours) and a sluggish desktop."))
        }
        if (arch == GuestArch.X64 && c.isArm64) out.add(Advice(Severity.WARNING,
            "Windows x64 on an ARM phone is full CPU emulation, several times slower than ARM64 Windows. Prefer a Windows 11 ARM64 image if you have one."))
        if (arch == GuestArch.X64) out.add(Advice(Severity.INFO,
            "Windows 11 x64 also expects TPM 2.0 and Secure Boot, which this stage does not provide. Windows 10 x64 images avoid that requirement."))
        if (ramMb > maxRamMb(c)) out.add(Advice(Severity.WARNING,
            "$ramMb MB exceeds the safe limit for this device (${maxRamMb(c)} MB). Android may kill the VM."))
        if (ramMb < 4096 && arch != GuestArch.UNKNOWN) out.add(Advice(Severity.WARNING,
            "Windows 11 requires 4 GB of RAM; Windows 10 runs better with 4 GB too."))
        if (cores > maxCores(c)) out.add(Advice(Severity.WARNING,
            "$cores virtual cores is more than this device can spare (${maxCores(c)}). Keep at least one core for Android."))
        if (c.freeStorageMb < 40L * 1024) out.add(Advice(Severity.WARNING,
            "Only ${c.freeStorageMb / 1024} GB free. A Windows install typically writes 20–30 GB even though the virtual disk file starts sparse."))
        if (diskGb < 48) out.add(Advice(Severity.WARNING, "Windows 11 needs at least 64 GB of virtual disk; 48+ GB is a practical minimum."))
        return out
    }
}
