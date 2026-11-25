package com.vantora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.vantora.app.apk.InstalledApp
import com.vantora.app.core.Category
import com.vantora.app.core.LibraryEntry

@Composable
fun AndroidScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsState()
    val apks = entries.filter { it.category == Category.APK }
    val picker = rememberLauncherForActivityResult(OpenDocs()) { vm.importUris(it) }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Android Applications", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("APKs are installed by Android's own package installer and run as normal Android apps. They never run inside Windows.", style = MaterialTheme.typography.bodySmall, color = VColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }) { Text("Import APK") }
            FilterChip(tab == 0, { tab = 0 }, { Text("Imported APKs") })
            FilterChip(tab == 1, { tab = 1; vm.refreshInstalled() }, { Text("Installed apps") })
        }
        if (tab == 0) ApkList(vm, apks) else InstalledList(vm)
    }
}

@Composable
private fun ApkList(vm: AppViewModel, apks: List<LibraryEntry>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (apks.isEmpty()) item { Empty("No APKs imported. Split bundles (.apks/.xapk) are not supported yet.") }
        items(apks, key = { it.id }) { e ->
            LaunchedEffect(e.id, vm.apkRefresh) { vm.apkInfos.remove(e.id); vm.loadApk(e) }
            val info = vm.apkInfos[e.id]
            var menu by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    info?.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) } ?: Spacer(Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.label ?: e.title, style = MaterialTheme.typography.titleMedium)
                        if (info != null) {
                            Text(info.packageName, style = MaterialTheme.typography.bodySmall, color = VColors.Muted)
                            Text("v${info.versionName ?: "?"} (${info.versionCode}) · min SDK ${info.minSdk} · target ${info.targetSdk} · ${info.permissions.size} permissions", style = MaterialTheme.typography.bodySmall)
                            Pill(if (info.installed) "Installed ${info.installedVersion ?: ""}" else "Not installed", if (info.installed) VColors.Ok else VColors.Muted)
                        } else Text(if (vm.apkInfos.containsKey(e.id)) "Could not read this APK (is it a split bundle?)" else "Reading…", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.installApk(e) }, enabled = info != null) { Text(if (info?.installed == true) "Update" else "Install") }
                            OutlinedButton(onClick = { info?.let { vm.launchPkg(it.packageName, e) } }, enabled = info?.installed == true) { Text("Open") }
                            Box {
                                TextButton(onClick = { menu = true }, enabled = info != null) { Text("Manage") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("App info") }, { menu = false; info?.let { vm.appSettings(it.packageName) } })
                                    DropdownMenuItem({ Text("Add shortcut") }, { menu = false; info?.let { vm.pin(it.packageName, it.label) } })
                                    DropdownMenuItem({ Text("Uninstall") }, { menu = false; info?.let { vm.uninstall(it.packageName) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledList(vm: AppViewModel) {
    var showSystem by remember { mutableStateOf(false) }
    val list = vm.installed.filter { showSystem || !it.system }
    Row(verticalAlignment = Alignment.CenterVertically) { Switch(showSystem, { showSystem = it }); Spacer(Modifier.width(8.dp)); Text("Include system apps") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.pkg }) { a -> InstalledRow(vm, a) }
    }
}

@Composable
private fun InstalledRow(vm: AppViewModel, a: InstalledApp) {
    var icon by remember(a.pkg) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(a.pkg) { icon = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.appIcon(a.pkg)?.asImageBitmap() } }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: Spacer(Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(a.label); Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = VColors.Muted) }
            TextButton(onClick = { vm.launchPkg(a.pkg) }) { Text("Open") }
            TextButton(onClick = { vm.appSettings(a.pkg) }) { Text("Manage") }
        }
    }
}
