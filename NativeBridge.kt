package com.vantora.app.device

/** Thin wrapper around libvantora_native.so (see src/main/cpp/native_bridge.cpp). */
object NativeBridge {
    const val STILL_RUNNING = -1000

    val available: Boolean = try {
        System.loadLibrary("vantora_native"); true
    } catch (t: Throwable) { false }

    @JvmStatic external fun nKvm(): Boolean
    @JvmStatic external fun nHwcap(): Long
    @JvmStatic external fun nSeekable(fd: Int): Boolean
    @JvmStatic external fun nSpawn(argv: Array<String>, dir: String, log: String, keep: IntArray): Int
    @JvmStatic external fun nWait(pid: Int, block: Boolean): Int
    @JvmStatic external fun nKill(pid: Int, sig: Int): Int

    fun kvmAccessible() = available && nKvm()
    fun hwcap() = if (available) nHwcap() else 0L
    fun isSeekable(fd: Int) = available && nSeekable(fd)
    fun spawn(argv: List<String>, dir: String, log: String, keep: IntArray): Int =
        if (available) nSpawn(argv.toTypedArray(), dir, log, keep) else -1
    fun waitPid(pid: Int, block: Boolean) = if (available) nWait(pid, block) else 255
    fun kill(pid: Int, sig: Int) = if (available) nKill(pid, sig) else -1
}
