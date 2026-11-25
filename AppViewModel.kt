package com.vantora.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vantora.app.apk.ApkInfo
import com.vantora.app.apk.ApkManager
import com.vantora.app.apk.InstalledApp
import com.vantora.app.compat.CompatReport
import com.vantora.app.compat.LaunchResult
import com.vantora.app.core.GuestArch
import com.vantora.app.core.LibraryEntry
import com.vantora.app.core.ServiceLocator
import com.vantora.app.core.VmEntity
import com.vantora.app.device.Advice
import com.vantora.app.device.DeviceCaps
import com.vantora.app.device.DeviceProbe
import com.vantora.app.library.LibraryMeta
import com.vantora.app.update.UpdateManager
import com.vantora.app.vm.VmController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val sl = ServiceLocator

    val entries = sl.library.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val vms = sl.db.vmDao().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val caps = MutableStateFlow<DeviceCaps?>(null)
    val engine = MutableStateFlow<List<Advice>>(emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    var wizardDone by mutableStateOf(sl.prefs.getBoolean("wizard_done", false))
        private set
    var updateRepo by mutableStateOf(sl.prefs.getString("update_repo", "") ?: "")
        private set
    var apkRefresh by mutableIntStateOf(0)
        private set
    val apkInfos: SnapshotStateMap<Long, ApkInfo?> = mutableStateMapOf()
    var installed by mutableStateOf<List<InstalledApp>>(emptyList())
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val c = DeviceProbe.probe(app)
            caps.value = c
            engine.value = sl.vmManager.engineStatus(c)
        }
        viewModelScope.launch { vms.collect { sl.prefsHasVm = it.isNotEmpty() } }
        viewModelScope.launch { ApkManager.events.collect { say(it); apkInfos.clear(); apkRefresh++; refreshInstalled() } }
        viewModelScope.launch(Dispatchers.IO) { sl.library.rescan() }
        refreshInstalled()
    }

    fun say(s: String) { messages.tryEmit(s) }
    fun finishWizard() { sl.prefs.edit().putBoolean("wizard_done", true).apply(); wizardDone = true }
    fun setUpdateRepo(r: String) { updateRepo = r; sl.prefs.edit().putString("update_repo", r).apply() }

    // ---- library ----
    fun importUris(uris: List<Uri>) = viewModelScope.launch {
        var ok = 0; var fail = 0
        for (u in uris) sl.library.import(u).onSuccess { ok++ }.onFailure { fail++; say("Import failed: ${it.message}") }
        if (ok > 0) say("Imported $ok file(s).")
    }

    fun importOne(uri: Uri, done: (LibraryEntry?) -> Unit) = viewModelScope.launch {
        val r = sl.library.import(uri)
        r.onFailure { say("Import failed: ${it.message}") }
        done(r.getOrNull())
    }

    fun rescan() = viewModelScope.launch { val m = sl.library.rescan(); say(if (m == 0) "All files are reachable." else "$m file(s) are missing – use Reconnect.") }
    fun remove(e: LibraryEntry) = viewModelScope.launch { sl.library.remove(e); say("Removed from library (your file was not touched).") }
    fun rename(e: LibraryEntry, n: String) = viewModelScope.launch { sl.library.rename(e, n) }
    fun toggleFavorite(e: LibraryEntry) = viewModelScope.launch { sl.library.toggleFavorite(e) }
    fun reconnect(e: LibraryEntry, uri: Uri) = viewModelScope.launch {
        sl.library.reconnect(e, uri).onSuccess { say("Reconnected '${e.title}'.") }.onFailure { say(it.message ?: "Reconnect failed") }
    }

    // ---- virtual computers ----
    fun createVm(name: String, arch: GuestArch, ram: Int, cores: Int, disk: Int, w: Int, h: Int, net: Boolean, media: Long?, done: (Result<VmEntity>) -> Unit) =
        viewModelScope.launch {
            done(runCatching { sl.vmManager.create(name, arch, ram, cores, disk, w, h, net, media) })
        }

    fun startVm(v: VmEntity) { VmController.start(app, v)?.let { say(it) } }
    fun saveVm(old: VmEntity, new: VmEntity) = viewModelScope.launch { runCatching { sl.vmManager.updateConfig(old, new) }.onFailure { say("Could not save: ${it.message}") } }
    fun detachMedia(v: VmEntity) = viewModelScope.launch { sl.vmManager.detachMedia(v); say("Install media detached; the VM will boot from its disk.") }
    fun deleteVm(v: VmEntity) = viewModelScope.launch { sl.vmManager.delete(v); say("Virtual computer deleted.") }
    fun engineBundled(arch: GuestArch) = sl.vmManager.engineBinary(arch) != null

    // ---- windows apps ----
    fun evaluate(e: LibraryEntry): CompatReport? {
        val c = caps.value ?: return null
        return sl.compat.evaluate(LibraryMeta.win(e), c)
    }

    fun launchWindows(e: LibraryEntry) = viewModelScope.launch {
        val report = evaluate(e) ?: return@launch say("Device probe still running…")
        val backend = sl.compat.backend(report.backendId)
        if (backend == null) { say(report.summary); return@launch }
        val (args, env) = LibraryMeta.args(e)
        when (val r = backend.launch(LibraryMeta.win(e), args, env)) {
            is LaunchResult.Started -> { sl.library.markUsed(e); say(r.message) }
            is LaunchResult.NotAvailable -> say(r.reason)
        }
    }

    fun saveWinConfig(e: LibraryEntry, args: String, env: String) = viewModelScope.launch { sl.library.saveConfig(e, args, env) }

    // ---- android apps ----
    fun loadApk(e: LibraryEntry) = viewModelScope.launch {
        if (apkInfos.containsKey(e.id)) return@launch
        apkInfos[e.id] = sl.apk.inspect(Uri.parse(e.uri))
    }

    fun installApk(e: LibraryEntry) = viewModelScope.launch {
        runCatching { sl.apk.install(Uri.parse(e.uri)); sl.library.markUsed(e) }.onFailure { say("Install failed: ${it.message}") }
    }

    fun launchPkg(pkg: String, e: LibraryEntry? = null) = viewModelScope.launch {
        if (!sl.apk.launch(pkg)) say("This app has no launchable screen or is not installed.") else e?.let { sl.library.markUsed(it) }
    }

    fun appSettings(pkg: String) = sl.apk.openAppSettings(pkg)
    fun uninstall(pkg: String) = sl.apk.uninstall(pkg)
    fun pin(pkg: String, label: String) = say(if (sl.apk.pinShortcut(pkg, label)) "Shortcut requested." else "Shortcuts are not supported by your launcher.")
    fun refreshInstalled() { viewModelScope.launch(Dispatchers.IO) { installed = sl.apk.installedLaunchable() } }
    fun appIcon(pkg: String) = sl.apk.icon(pkg)

    // ---- updates ----
    fun checkUpdates(current: String) = viewModelScope.launch {
        UpdateManager.check(updateRepo, current)
            .onSuccess { say(if (it.newer) "Update available: ${it.latest} – ${it.url}" else "You are up to date (${it.latest}).") }
            .onFailure { say(it.message ?: "Update check failed") }
    }
}
