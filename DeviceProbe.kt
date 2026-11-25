package com.vantora.app.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import java.io.File

object DeviceProbe {
    fun probe(ctx: Context): DeviceCaps {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val free = try { StatFs(dir.path).availableBytes / (1024 * 1024) } catch (t: Throwable) { 0L }
        val (model, features) = readCpu()
        val (renderer, vendor) = queryGl()
        val vk = ctx.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        val vkText = vk?.let { "${it shr 22}.${(it shr 12) and 0x3ff}" }
        return DeviceCaps(
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuModel = model,
            cpuFeatures = features,
            totalRamMb = mi.totalMem / (1024 * 1024),
            availRamMb = mi.availMem / (1024 * 1024),
            freeStorageMb = free,
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            glEs = am.deviceConfigurationInfo.glEsVersion ?: "?",
            glRenderer = renderer,
            glVendor = vendor,
            vulkanApi = vkText,
            kvmAccessible = NativeBridge.kvmAccessible()
        )
    }

    private fun readCpu(): Pair<String, Set<String>> {
        var model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        var feats = emptySet<String>()
        try {
            File("/proc/cpuinfo").forEachLine { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    val k = line.substring(0, i).trim().lowercase()
                    val v = line.substring(i + 1).trim()
                    if (k == "features" && feats.isEmpty()) feats = v.split(' ').filter { it.isNotBlank() }.toSet()
                    if ((k == "hardware" || k == "model name") && v.isNotBlank()) model = v
                }
            }
        } catch (_: Throwable) { }
        return model to feats
    }

    private fun queryGl(): Pair<String, String> = try {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val r = (GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown") to (GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown")
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf)
        EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglTerminate(dpy)
        r
    } catch (t: Throwable) { "unknown" to "unknown" }
}
