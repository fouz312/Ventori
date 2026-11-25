package com.vantora.app.apk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.Uri.fromParts
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

data class ApkInfo(
    val packageName: String, val label: String, val versionName: String?, val versionCode: Long,
    val minSdk: Int, val targetSdk: Int, val permissions: List<String>, val icon: Bitmap?,
    val installed: Boolean, val installedVersion: String?
)

data class InstalledApp(val pkg: String, val label: String, val system: Boolean)

/**
 * Android APKs are installed by Android itself (PackageInstaller) and run as ordinary Android apps.
 * This has nothing to do with the Windows VM or the Windows compatibility backends.
 * Not supported yet: split bundles (.apks / .xapk / .apkm).
 */
class ApkManager(private val ctx: Context) {
    companion object {
        const val ACTION = "com.vantora.app.APK_INSTALL_RESULT"
        val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    }

    private val pm get() = ctx.packageManager

    suspend fun inspect(uri: Uri): ApkInfo? = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "inspect-${uri.hashCode()}.apk")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: return@withContext null
            @Suppress("DEPRECATION")
            val pi = pm.getPackageArchiveInfo(tmp.path, PackageManager.GET_PERMISSIONS) ?: return@withContext null
            val ai = pi.applicationInfo ?: return@withContext null
            ai.sourceDir = tmp.path; ai.publicSourceDir = tmp.path
            val label = ai.loadLabel(pm).toString()
            val icon = try { ai.loadIcon(pm).toBitmap(96, 96) } catch (t: Throwable) { null }
            @Suppress("DEPRECATION")
            val inst = try { pm.getPackageInfo(pi.packageName, 0) } catch (e: PackageManager.NameNotFoundException) { null }
            ApkInfo(pi.packageName, label, pi.versionName, PackageInfoCompat.getLongVersionCode(pi),
                ai.minSdkVersion, ai.targetSdkVersion, pi.requestedPermissions?.toList() ?: emptyList(), icon,
                inst != null, inst?.versionName)
        } catch (t: Throwable) { null } finally { tmp.delete() }
    }

    fun isInstalled(pkg: String) = try { @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    /** Installs through Android's official PackageInstaller; the system shows its own confirmation. */
    suspend fun install(uri: Uri) = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { s ->
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                s.openWrite("base.apk", 0, -1).use { out -> inp.copyTo(out); s.fsync(out) }
            } ?: error("Cannot open the APK")
            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    fun launch(pkg: String): Boolean {
        val i = pm.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }

    fun openAppSettings(pkg: String) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun uninstall(pkg: String) {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun installedLaunchable(): List<InstalledApp> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(q, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun icon(pkg: String): Bitmap? = try { pm.getApplicationIcon(pkg).toBitmap(96, 96) } catch (t: Throwable) { null }

    fun pinShortcut(pkg: String, label: String): Boolean {
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val bmp = icon(pkg) ?: return false
        val info = ShortcutInfoCompat.Builder(ctx, "app-$pkg").setShortLabel(label).setIcon(IconCompat.createWithBitmap(bmp)).setIntent(launch).build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkManager.events.tryEmit("Installed successfully.")
            else -> ApkManager.events.tryEmit("Install did not complete ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"}")
        }
    }
}
