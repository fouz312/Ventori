package com.vantora.app.compat

import com.vantora.app.core.BinSearch
import java.nio.channels.FileChannel

enum class PeMachine { X86, X64, ARM64, ARM, UNKNOWN }

data class PeInfo(
    val machine: PeMachine,
    val isDll: Boolean,
    val subsystem: Int,
    val isDotNet: Boolean,
    val imports: List<String>,
    val tags: Set<String>,
    val sizeBytes: Long
)

/** Reads real PE/COFF headers: CPU architecture, subsystem, .NET flag, imported DLL names. */
object PeAnalyzer {
    private val tagPatterns = linkedMapOf(
        "roblox" to "roblox", "hyperion" to "hyperion", "byfron" to "byfron",
        "easyanticheat" to "easyanticheat", "battleye" to "battleye", "vanguard" to "vanguard"
    )

    fun isMsi(ch: FileChannel): Boolean {
        val b = BinSearch.read(ch, 0, 8)
        val sig = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        return b.size == 8 && sig.indices.all { (b[it].toInt() and 0xFF) == sig[it] }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    fun analyze(ch: FileChannel): PeInfo? {
        val size = ch.size()
        if (size < 0x100) return null
        val dos = BinSearch.read(ch, 0, 64)
        if (dos.size < 64 || dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
        val peOff = u32(dos, 0x3C)
        if (peOff <= 0 || peOff + 264 > size) return null
        val h = BinSearch.read(ch, peOff, 264)
        if (h.size < 264 || h[0] != 'P'.code.toByte() || h[1] != 'E'.code.toByte() || h[2].toInt() != 0 || h[3].toInt() != 0) return null

        val machineCode = u16(h, 4)
        val nSec = u16(h, 6).coerceAtMost(96)
        val optSize = u16(h, 20)
        val characteristics = u16(h, 22)
        val opt = 24
        val magic = u16(h, opt)
        val plus = magic == 0x20B
        if (magic != 0x10B && !plus) return null
        val subsystem = u16(h, opt + 68)
        val nDirs = u32(h, opt + if (plus) 108 else 92)
        val dd = opt + if (plus) 112 else 96

        val machine = when (machineCode) {
            0x8664 -> PeMachine.X64
            0xAA64 -> PeMachine.ARM64
            0x014C -> PeMachine.X86
            0x01C4, 0x01C0 -> PeMachine.ARM
            else -> PeMachine.UNKNOWN
        }

        // section table for RVA -> file offset
        val secs = BinSearch.read(ch, peOff + 24 + optSize, nSec * 40)
        fun rva2off(rva: Long): Long {
            for (i in 0 until secs.size / 40) {
                val va = u32(secs, i * 40 + 12)
                val vs = u32(secs, i * 40 + 8)
                val raw = u32(secs, i * 40 + 16)
                val ptr = u32(secs, i * 40 + 20)
                if (rva >= va && rva < va + maxOf(vs, raw)) return rva - va + ptr
            }
            return -1
        }

        val imports = mutableListOf<String>()
        val importRva = if (nDirs > 1) u32(h, dd + 8) else 0L
        if (importRva != 0L) {
            val off = rva2off(importRva)
            if (off > 0) {
                val d = BinSearch.read(ch, off, 20 * 128)
                var i = 0
                while ((i + 1) * 20 <= d.size) {
                    val nameRva = u32(d, i * 20 + 12)
                    if (nameRva == 0L) break
                    val no = rva2off(nameRva)
                    if (no > 0) {
                        val nb = BinSearch.read(ch, no, 64)
                        val end = nb.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) nb.size else it }
                        imports += String(nb, 0, end, Charsets.US_ASCII).lowercase()
                    }
                    i++
                }
            }
        }
        val dotnet = nDirs > 14 && u32(h, dd + 14 * 8) != 0L
        val tags = BinSearch.scan(ch, 16L * 1024 * 1024, tagPatterns)
        return PeInfo(machine, (characteristics and 0x2000) != 0, subsystem, dotnet, imports, tags, size)
    }
}
