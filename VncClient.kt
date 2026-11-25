package com.vantora.app.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors

/**
 * RFB (VNC) 3.8 client over a Unix socket. This is how the REAL framebuffer produced by the QEMU virtual
 * graphics device reaches the screen. Encodings: Raw + DesktopSize. Security: None (socket is private to the app).
 */
class VncClient(private val socketPath: String, private val listener: Listener) {
    interface Listener {
        fun onFrame()
        fun onClipboard(text: String)
        fun onClosed(reason: String?)
    }

    val lock = Any()
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var pixels: IntArray = IntArray(0)
        private set

    private var socket: LocalSocket? = null
    private var inp: DataInputStream? = null
    private var out: DataOutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    fun start() { Thread({ run() }, "vnc-reader").start() }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) { }
        sender.shutdownNow()
    }

    private fun fail(msg: String): Nothing = throw java.io.IOException(msg)

    private fun run() {
        try {
            val s = LocalSocket()
            socket = s
            s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val i = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
            val o = DataOutputStream(BufferedOutputStream(s.outputStream, 1 shl 14))
            inp = i; out = o

            val ver = ByteArray(12); i.readFully(ver)
            o.write("RFB 003.008\n".toByteArray()); o.flush()
            val n = i.readUnsignedByte()
            if (n == 0) { val l = i.readInt(); val r = ByteArray(l); i.readFully(r); fail("Server refused: ${String(r)}") }
            val types = ByteArray(n); i.readFully(types)
            if (types.none { it.toInt() == 1 }) fail("Unsupported VNC authentication")
            o.write(1); o.flush()
            if (i.readInt() != 0) fail("VNC security handshake failed")
            o.write(1); o.flush()                         // ClientInit: shared
            val w = i.readUnsignedShort(); val h = i.readUnsignedShort()
            i.skipBytes(16)                                // server pixel format
            i.skipBytes(i.readInt())                       // desktop name
            resize(w, h)

            synchronized(o) {                              // SetPixelFormat: 32bpp true colour, R<<16 G<<8 B
                o.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1))
                o.writeShort(255); o.writeShort(255); o.writeShort(255)
                o.write(16); o.write(8); o.write(0); o.write(byteArrayOf(0, 0, 0)); o.flush()
            }
            synchronized(o) {                              // SetEncodings: Raw, DesktopSize
                o.write(2); o.write(0); o.writeShort(2); o.writeInt(0); o.writeInt(-223); o.flush()
            }
            requestUpdate(false)

            var line = ByteArray(0)
            while (!closed) {
                when (i.readUnsignedByte()) {
                    0 -> {
                        i.skipBytes(1)
                        val rects = i.readUnsignedShort()
                        repeat(rects) {
                            val x = i.readUnsignedShort(); val y = i.readUnsignedShort()
                            val rw = i.readUnsignedShort(); val rh = i.readUnsignedShort()
                            when (val enc = i.readInt()) {
                                0 -> {
                                    if (line.size < rw * 4) line = ByteArray(rw * 4)
                                    for (row in 0 until rh) {
                                        i.readFully(line, 0, rw * 4)
                                        synchronized(lock) {
                                            val base = (y + row) * width + x
                                            if (y + row < height && x + rw <= width) {
                                                for (c in 0 until rw) {
                                                    val b = line[c * 4].toInt() and 0xFF
                                                    val g = line[c * 4 + 1].toInt() and 0xFF
                                                    val r = line[c * 4 + 2].toInt() and 0xFF
                                                    pixels[base + c] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                                }
                                            }
                                        }
                                    }
                                }
                                -223 -> resize(rw, rh)
                                else -> fail("Unsupported VNC encoding $enc")
                            }
                        }
                        listener.onFrame()
                        requestUpdate(true)
                    }
                    1 -> { i.skipBytes(1); i.skipBytes(2); val cnt = i.readUnsignedShort(); i.skipBytes(cnt * 6) }
                    2 -> { }
                    3 -> {
                        i.skipBytes(3)
                        val len = i.readInt()
                        val b = ByteArray(len.coerceIn(0, 1 shl 20)); i.readFully(b)
                        if (len > b.size) i.skipBytes(len - b.size)
                        listener.onClipboard(String(b, Charsets.ISO_8859_1))
                    }
                    else -> fail("Unknown VNC message")
                }
            }
        } catch (t: Throwable) {
            if (!closed) listener.onClosed(t.message)
        }
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w; height = h
            pixels = IntArray(w * h)
        }
    }

    private fun requestUpdate(incremental: Boolean) {
        val o = out ?: return
        synchronized(o) {
            o.write(3); o.write(if (incremental) 1 else 0)
            o.writeShort(0); o.writeShort(0); o.writeShort(width); o.writeShort(height); o.flush()
        }
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        val o = out ?: return
        try {
            sender.execute { try { synchronized(o) { block(o); o.flush() } } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    fun key(down: Boolean, keysym: Int) = send { it.write(4); it.write(if (down) 1 else 0); it.writeShort(0); it.writeInt(keysym) }

    fun pointer(mask: Int, x: Int, y: Int) = send { it.write(5); it.write(mask); it.writeShort(x); it.writeShort(y) }

    fun clipboard(text: String) = send {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        it.write(6); it.write(byteArrayOf(0, 0, 0)); it.writeInt(b.size); it.write(b)
    }
}
