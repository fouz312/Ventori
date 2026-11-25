package com.vantora.app.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Small byte-level helpers shared by the ISO inspector and the PE analyzer. */
object BinSearch {
    private const val OVERLAP = 64

    /** ASCII, UTF-16BE and UTF-16LE encodings of [s] (UDF and PE strings use the wide forms). */
    fun variants(s: String): List<ByteArray> {
        val a = s.toByteArray(Charsets.US_ASCII)
        val be = ByteArray(a.size * 2) { i -> if (i % 2 == 0) 0 else a[i / 2] }
        val le = ByteArray(a.size * 2) { i -> if (i % 2 == 0) a[i / 2] else 0 }
        return listOf(a, be, le)
    }

    fun indexOf(buf: ByteArray, n: Int, pat: ByteArray): Int {
        if (pat.isEmpty() || n < pat.size) return -1
        val first = pat[0]
        val last = n - pat.size
        var i = 0
        while (i <= last) {
            if (buf[i] == first) {
                var j = 1
                while (j < pat.size && buf[i + j] == pat[j]) j++
                if (j == pat.size) return i
            }
            i++
        }
        return -1
    }

    fun readInto(ch: FileChannel, pos: Long, buf: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(buf, 0, len)
        var total = 0
        while (bb.hasRemaining()) {
            val r = ch.read(bb, pos + total)
            if (r < 0) break
            total += r
        }
        return total
    }

    fun read(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val size = ch.size()
        if (pos < 0 || pos >= size) return ByteArray(0)
        val l = minOf(len.toLong(), size - pos).toInt()
        val b = ByteArray(l)
        val n = readInto(ch, pos, b, l)
        return if (n == l) b else b.copyOf(n)
    }

    /**
     * Case-insensitively looks for each pattern (lowercase ASCII) in the first [limit] bytes.
     * Returns the keys of [patterns] that were found.
     */
    fun scan(ch: FileChannel, limit: Long, patterns: Map<String, String>, chunk: Int = 4 shl 20): Set<String> {
        val size = ch.size()
        val end = minOf(size, limit)
        val pats = patterns.map { (tag, s) -> tag to variants(s) }
        val found = linkedSetOf<String>()
        val buf = ByteArray(chunk + OVERLAP)
        var pos = 0L
        while (pos < end) {
            val want = minOf(buf.size.toLong(), size - pos).toInt()
            val n = readInto(ch, pos, buf, want)
            if (n <= 0) break
            for (i in 0 until n) {
                val b = buf[i].toInt()
                if (b in 65..90) buf[i] = (b + 32).toByte()
            }
            for ((tag, vs) in pats) {
                if (tag !in found && vs.any { indexOf(buf, n, it) >= 0 }) found += tag
            }
            pos += chunk
        }
        return found
    }
}
