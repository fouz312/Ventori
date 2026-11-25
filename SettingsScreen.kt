package com.vantora.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vantora.app.device.Feasibility

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val caps by vm.caps.collectAsState()
    val engine by vm.engine.collectAsState()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var repo by remember { mutableStateOf(vm.updateRepo) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp)) }
        val c = caps
        if (c == null) item { CircularProgressIndicator() } else {
            item { Section("Device information") {
                KeyValue("Device", c.device); KeyValue("Android", "${c.androidRelease} (API ${c.androidSdk})"); KeyValue("ABIs", c.abis.joinToString())
            } }
            item { Section("CPU") {
                KeyValue("Model", c.cpuModel); KeyValue("Cores", c.cpuCores.toString())
                KeyValue("Safe VM cores", Feasibility.maxCores(c).toString())
                KeyValue("Features", c.cpuFeatures.take(14).joinToString(" ") + if (c.cpuFeatures.size > 14) " …" else "")
            } }
            item { Section("RAM & storage") {
                KeyValue("Total RAM", "${c.totalRamMb} MB"); KeyValue("Available now", "${c.availRamMb} MB")
                KeyValue("Safe VM RAM", "${Feasibility.maxRamMb(c)} MB"); KeyValue("Free storage", "${c.freeStorageMb / 1024} GB")
            } }
            item { Section("Graphics") {
                KeyValue("GPU", c.glRenderer); KeyValue("Vendor", c.glVendor); KeyValue("OpenGL ES", c.glEs); KeyValue("Vulkan", c.vulkanApi ?: "not reported")
                Text("The guest sees a simple framebuffer (ramfb / VGA). Windows 3D acceleration is not available in stage 1.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { Section("Virtualization") {
            if (engine.isEmpty()) Text("Checking…")
            engine.forEach { AdviceRow(it) }
            KeyValue("Hardware virtualization", if (c?.kvmAccessible == true) "/dev/kvm accessible" else "unavailable (software emulation)")
        } }
        item { Section("Input") {
            Text("Touch: tap = left click, long-press or two-finger tap = right click, two-finger drag = scroll. Use the viewer's Drag chip to hold the left button. Bluetooth/USB mice and keyboards are forwarded directly. Game controllers: not implemented.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Section("Updates") {
            KeyValue("Installed version", version)
            OutlinedTextField(repo, { repo = it; vm.setUpdateRepo(it) }, label = { Text("GitHub repository (owner/name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.checkUpdates(version) }) { Text("Check for updates") }
        } }
        item { Section("About") {
            Text("Vantora $version — QEMU (GPLv2), edk2/OVMF (BSD-2-Clause-Patent), GLib, pixman, libslirp, AndroidX/Compose (Apache-2.0). See THIRD_PARTY.md.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
