package com.vantora.app

import com.vantora.app.compat.*
import com.vantora.app.core.GuestArch
import com.vantora.app.device.DeviceCaps
import com.vantora.app.device.Feasibility
import com.vantora.app.device.Severity
import com.vantora.app.media.MediaFormat
import com.vantora.app.media.MediaInspector
import com.vantora.app.update.UpdateManager
import com.vantora.app.vm.QemuCommand
import com.vantora.app.vm.QemuLaunchSpec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class CoreLogicTest {
    private fun caps(abis: List<String> = listOf("arm64-v8a"), kvm: Boolean = false, ram: Long = 12000) = DeviceCaps(
        abis, 8, "test", emptySet(), ram, ram / 2, 200_000, 34, "14", "Test", "3.2", "GPU", "V", "1.3", kvm)

    private fun tmp(bytes: ByteArray): RandomAccessFile {
        val f = File.createTempFile("vt", ".bin"); f.deleteOnExit(); f.writeBytes(bytes); return RandomAccessFile(f, "r")
    }

    /** Minimal PE32+ with the given machine and one import (kernel32.dll). */
    private fun buildPe(machine: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteArray(0x800)
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte(); b[0x3C] = 0x80.toByte()
        val pe = 0x80
        b[pe] = 'P'.code.toByte(); b[pe + 1] = 'E'.code.toByte()
        fun w16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun w32(o: Int, v: Int) { w16(o, v and 0xFFFF); w16(o + 2, v ushr 16) }
        w16(pe + 4, machine); w16(pe + 6, 1); w16(pe + 20, 240); w16(pe + 22, 0x22)
        val opt = pe + 24
        w16(opt, 0x20B); w16(opt + 68, 3); w32(opt + 108, 16)
        val dd = opt + 112
        w32(dd + 8, 0x1000)                         // import dir RVA
        val sec = opt + 240
        w32(sec + 8, 0x400); w32(sec + 12, 0x1000); w32(sec + 16, 0x400); w32(sec + 20, 0x400)  // VA 0x1000 -> raw 0x400
        // import descriptor at raw 0x400: name RVA 0x1100 -> raw 0x500
        w32(0x400 + 12, 0x1100)
        "kernel32.dll".toByteArray().copyInto(b, 0x500)
        extra.copyInto(b, 0x600)
        return b
    }

    @Test fun peX64() {
        val i = PeAnalyzer.analyze(tmp(buildPe(0x8664)).channel)!!
        assertEquals(PeMachine.X64, i.machine); assertTrue("kernel32.dll" in i.imports); assertEquals(3, i.subsystem)
    }

    @Test fun peArm64() = assertEquals(PeMachine.ARM64, PeAnalyzer.analyze(tmp(buildPe(0xAA64)).channel)!!.machine)

    @Test fun notPe() = assertNull(PeAnalyzer.analyze(tmp(ByteArray(1024)).channel))

    @Test fun robloxTagDetected() {
        val i = PeAnalyzer.analyze(tmp(buildPe(0x8664, "RobloxPlayerInstaller".toByteArray())).channel)!!
        assertTrue("roblox" in i.tags)
    }

    @Test fun robloxIsUnsupportedNeverClaimedWorking() {
        val eng = CompatEngine(File("/nonexistent")) { false }
        val app = WinAppInfo("RobloxPlayerInstaller.exe", false, PeMachine.X64, 2, false, emptyList(), setOf("roblox"))
        val r = eng.evaluate(app, caps())
        assertEquals(CompatStatus.UNSUPPORTED, r.status); assertNull(r.backendId)
    }

    @Test fun x64OnArmNeedsRuntime() {
        val eng = CompatEngine(File("/nonexistent")) { false }
        val r = eng.evaluate(WinAppInfo("a.exe", false, PeMachine.X64, 2, false, listOf("d3d11.dll"), emptySet()), caps())
        assertEquals(CompatStatus.BACKEND_MISSING, r.status); assertEquals("wine-box64", r.backendId)
        assertTrue(r.dependencies.any { it.contains("DXVK") })
    }

    @Test fun x86WithoutAarch32Unsupported() {
        val eng = CompatEngine(File("/nonexistent")) { false }
        // only the VM backend "supports" it; it must not be reported as natively runnable
        val r = eng.evaluate(WinAppInfo("a.exe", false, PeMachine.X86, 2, false, emptyList(), emptySet()), caps())
        assertNotEquals(CompatStatus.EXPERIMENTAL, r.status)
    }

    @Test fun isoArchDetection() {
        val iso = ByteArray(0x20000)
        iso[0x8000] = 1; "CD001".toByteArray().copyInto(iso, 0x8001)
        "WIN11_ARM64".toByteArray().copyInto(iso, 0x8028)
        "EFI/BOOT/BOOTAA64.EFI".toByteArray().copyInto(iso, 0x10000)
        "sources/install.wim".toByteArray().copyInto(iso, 0x10100)
        val m = MediaInspector.inspectChannel(tmp(iso).channel, "x.iso")
        assertEquals(MediaFormat.ISO, m.format); assertEquals(GuestArch.ARM64, m.arch)
        assertEquals("WIN11_ARM64", m.volumeLabel); assertTrue(m.windowsLike)
    }

    @Test fun isoX64Utf16() {
        val iso = ByteArray(0x20000)
        iso[0x8000] = 1; "CD001".toByteArray().copyInto(iso, 0x8001)
        val name = "bootx64.efi"
        name.forEachIndexed { i, ch -> iso[0x10000 + i * 2] = 0; iso[0x10000 + i * 2 + 1] = ch.code.toByte() }
        assertEquals(GuestArch.X64, MediaInspector.inspectChannel(tmp(iso).channel, "x.iso").arch)
    }

    @Test fun vhdxAndGarbage() {
        val v = ByteArray(4096); "vhdxfile".toByteArray().copyInto(v, 0)
        assertEquals(MediaFormat.VHDX, MediaInspector.inspectChannel(tmp(v).channel, "d.vhdx").format)
        assertEquals(MediaFormat.UNKNOWN, MediaInspector.inspectChannel(tmp(ByteArray(4096)).channel, "z.iso").format)
    }

    @Test fun feasibilityWarnsAboutSoftwareEmulationAndMissingEngine() {
        val a = Feasibility.assess(caps(), GuestArch.ARM64, 4096, 2, 64, engineBundled = false)
        assertTrue(a.any { it.severity == Severity.BLOCKER && it.text.contains("not bundled") })
        assertTrue(a.any { it.text.contains("software emulation") })
    }

    @Test fun ramLimitIsSafe() {
        val c = caps(ram = 8000)
        assertTrue(Feasibility.maxRamMb(c) <= 4000)
        assertTrue(Feasibility.maxCores(c) < c.cpuCores)
    }

    @Test fun qemuCommandArm64() {
        val s = QemuLaunchSpec(GuestArch.ARM64, 4096, 4, "/d/disk.raw", "/proc/self/fd/9", "/c.fd", "/v.fd", "/v.sock", "/q.sock", true, false)
        val c = QemuCommand.build("/bin/qemu", s)
        assertEquals("/bin/qemu", c[0])
        assertTrue(c.containsAll(listOf("-m", "4096", "-smp", "4", "ramfb")))
        assertTrue(c.any { it.contains("file=/proc/self/fd/9") && it.contains("media=cdrom") })
        assertTrue(c.any { it.startsWith("unix:/v.sock") })
        assertTrue(c.any { it.startsWith("nvme") })
    }

    @Test fun qemuNoIsoNoNetwork() {
        val s = QemuLaunchSpec(GuestArch.X64, 2048, 1, "/d", null, "/c", "/v", "/s", "/q", false, false)
        val c = QemuCommand.build("/q", s)
        assertTrue(c.none { it.contains("media=cdrom") }); assertTrue("-nic" in c); assertTrue("q35" in c.joinToString(" "))
    }

    @Test fun versionCompare() {
        assertTrue(UpdateManager.isNewer("v1.2.0", "1.1.9")); assertFalse(UpdateManager.isNewer("0.1.0", "0.1.0"))
        assertTrue(UpdateManager.isNewer("0.2", "0.1.5"))
    }
}
