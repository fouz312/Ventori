package com.vantora.app.media

import android.content.Context
import android.net.Uri
import com.vantora.app.core.BinSearch
import com.vantora.app.core.GuestArch
import java.io.FileInputStream
import java.nio.channels.FileChannel

enum class MediaFormat { ISO, RAW_DISK, VHD, VHDX, QCOW2, VMDK, UNKNOWN }

data class MediaInfo(
    val format: MediaFormat,
    val arch: GuestArch,
    val volumeLabel: String?,
    val sizeBytes: Long,
    val windowsLike: Boolean,
    val notes: List<String>
)

/**
 * Looks at the real bytes of an image. Architecture of an ISO is a HEURISTIC: Windows media contain
 * \EFI\BOOT\bootaa64.efi (ARM64) or bootx64.efi (x64); we search the first 48 MB (where the
 * ISO9660/UDF directory structures live) for those names.
 */
object MediaInspector {
    private const val SCAN_LIMIT = 48L * 1024 * 1024

    private val markers = linkedMapOf(
        "aa64" to "bootaa64.efi", "x64" to "bootx64.efi", "ia32" to "bootia32.efi",
        "wim" to "install.wim", "esd" to "install.esd", "bootwim" to "boot.wim", "setup" to "setup.exe"
    )

    fun inspect(ctx: Context, uri: Uri, name: String): MediaInfo? =
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            inspectChannel(FileInputStream(pfd.fileDescriptor).channel, name)
        }

    fun inspectChannel(ch: FileChannel, name: String): MediaInfo {
        val size = ch.size()
        val head = BinSearch.read(ch, 0, 1024)
        val tail = if (size >= 512) BinSearch.read(ch, size - 512, 512) else ByteArray(0)

        fun ByteArray.has(s: String, off: Int = 0): Boolean {
            val b = s.toByteArray(Charsets.US_ASCII)
            if (this.size < off + b.size) return false
            for (i in b.indices) if (this[off + i] != b[i]) return false
            return true
        }

        if (head.has("vhdxfile")) return MediaInfo(MediaFormat.VHDX, GuestArch.UNKNOWN, null, size, false,
            listOf("Hyper-V VHDX disk. Its architecture cannot be known without mounting it.",
                "Stage 1 creates new VM disks; attaching an existing disk is not implemented yet (QEMU itself can read VHDX)."))
        if (head.has("conectix") || tail.has("conectix")) return MediaInfo(MediaFormat.VHD, GuestArch.UNKNOWN, null, size, false,
            listOf("Microsoft VHD disk. Architecture unknown until mounted."))
        if (head.size >= 4 && head[0] == 'Q'.code.toByte() && head[1] == 'F'.code.toByte() && head[2] == 'I'.code.toByte() && head[3] == 0xFB.toByte())
            return MediaInfo(MediaFormat.QCOW2, GuestArch.UNKNOWN, null, size, false, listOf("QEMU qcow2 disk image."))
        if (head.has("KDMV")) return MediaInfo(MediaFormat.VMDK, GuestArch.UNKNOWN, null, size, false, listOf("VMware VMDK disk."))

        // ISO9660: "CD001" at 0x8001
        val pvd = BinSearch.read(ch, 0x8000, 2048)
        if (pvd.size >= 2048 && pvd[0].toInt() == 1 && pvd.has("CD001", 1)) {
            val label = String(pvd, 40, 32, Charsets.US_ASCII).trim().ifBlank { null }
            val notes = mutableListOf<String>()
            var udf = false
            for (sector in 16..40) {
                val id = BinSearch.read(ch, sector * 2048L + 1, 5)
                val s = String(id, Charsets.US_ASCII)
                if (s == "NSR02" || s == "NSR03" || s == "BEA01") udf = true
            }
            if (udf) notes += "ISO9660 + UDF bridge (typical for Windows media)."
            val found = BinSearch.scan(ch, SCAN_LIMIT, markers)
            val arch = when {
                "aa64" in found -> GuestArch.ARM64
                "x64" in found -> GuestArch.X64
                "ia32" in found -> GuestArch.X86
                else -> GuestArch.UNKNOWN
            }
            val windows = found.any { it in setOf("wim", "esd", "bootwim", "setup") } || arch != GuestArch.UNKNOWN
            notes += if (windows) "Looks like Windows setup media (heuristic)." else "No Windows setup markers found in the first 48 MB; this may not be Windows media."
            if (arch == GuestArch.UNKNOWN) notes += "Architecture could not be detected; choose it manually."
            return MediaInfo(MediaFormat.ISO, arch, label, size, windows, notes)
        }

        val gpt = BinSearch.read(ch, 512, 8)
        val mbr = head.size >= 512 && head[510] == 0x55.toByte() && head[511] == 0xAA.toByte()
        if (mbr || String(gpt, Charsets.US_ASCII) == "EFI PART")
            return MediaInfo(MediaFormat.RAW_DISK, GuestArch.UNKNOWN, null, size, false,
                listOf("Raw disk/USB image with a partition table. Architecture unknown until mounted."))

        return MediaInfo(MediaFormat.UNKNOWN, GuestArch.UNKNOWN, null, size, false,
            listOf("Not recognised as ISO9660, VHD/VHDX, qcow2, VMDK or a partitioned raw image ($name)."))
    }
}
