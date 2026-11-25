package com.vantora.app.vm

import com.vantora.app.core.GuestArch

data class QemuLaunchSpec(
    val arch: GuestArch,
    val ramMb: Int,
    val cores: Int,
    val diskPath: String,
    val isoPath: String?,
    val firmwareCode: String,
    val firmwareVars: String,
    val vncSocket: String,
    val qmpSocket: String,
    val network: Boolean,
    val kvm: Boolean
)

/**
 * Builds the QEMU command line. Hardware is chosen so an UNMODIFIED Windows installer can see it:
 *  - NVMe disk (inbox Windows driver), USB mass-storage CD (inbox), xHCI keyboard + tablet (inbox),
 *    Intel e1000e NIC (inbox), ramfb/VGA framebuffer for display.
 * VirtIO devices are intentionally NOT used for boot/install because Windows has no inbox VirtIO drivers.
 */
object QemuCommand {
    fun build(binary: String, s: QemuLaunchSpec): List<String> {
        val a = mutableListOf(binary, "-name", "vantora")
        when (s.arch) {
            GuestArch.ARM64 -> {
                a += listOf("-machine", "virt,gic-version=3")
                a += listOf("-cpu", if (s.kvm) "host" else "max")
            }
            else -> {
                a += listOf("-machine", "q35")
                a += listOf("-cpu", if (s.kvm) "host" else "max")
            }
        }
        a += listOf("-accel", if (s.kvm) "kvm" else "tcg,thread=multi")
        a += listOf("-smp", s.cores.toString(), "-m", s.ramMb.toString())

        a += listOf("-drive", "if=pflash,format=raw,unit=0,file=${s.firmwareCode},readonly=on")
        a += listOf("-drive", "if=pflash,format=raw,unit=1,file=${s.firmwareVars}")

        a += if (s.arch == GuestArch.ARM64) listOf("-device", "ramfb") else listOf("-vga", "std")

        a += listOf("-device", "qemu-xhci,id=xhci", "-device", "usb-kbd,bus=xhci.0", "-device", "usb-tablet,bus=xhci.0")

        a += listOf("-drive", "if=none,id=hd0,file=${s.diskPath},format=raw,cache=writeback,discard=unmap")
        a += listOf("-device", "nvme,serial=vantora0,drive=hd0,bootindex=1")

        if (s.isoPath != null) {
            a += listOf("-drive", "if=none,id=cd0,media=cdrom,file=${s.isoPath},format=raw,readonly=on")
            a += listOf("-device", "usb-storage,bus=xhci.0,drive=cd0,bootindex=0")
        }

        if (s.network) a += listOf("-netdev", "user,id=net0", "-device", "e1000e,netdev=net0")
        else a += listOf("-nic", "none")

        a += listOf("-rtc", "base=localtime")
        a += listOf("-display", "none", "-vnc", "unix:${s.vncSocket}")
        a += listOf("-qmp", "unix:${s.qmpSocket},server=on,wait=off")
        a += listOf("-serial", "none", "-monitor", "none", "-nodefaults")
        return a
    }
}
