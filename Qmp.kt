package com.vantora.app.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress

/** Minimal QMP client: one short connection per command. */
object Qmp {
    fun command(path: String, cmd: String): String {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 5000
            val rd = s.inputStream.bufferedReader()
            val wr = s.outputStream
            rd.readLine() // greeting
            wr.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray()); wr.flush()
            rd.readLine()
            wr.write("{\"execute\":\"$cmd\"}\n".toByteArray()); wr.flush()
            while (true) {
                val line = rd.readLine() ?: return "closed"
                if (line.contains("\"return\"") || line.contains("\"error\"")) return line
            }
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }
}
