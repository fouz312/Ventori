package com.vantora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vantora.app.R
import com.vantora.app.core.Category
import com.vantora.app.device.Severity
import com.vantora.app.vm.VmController
import com.vantora.app.vm.VmRuntime

@Composable
fun HomeScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val vms by vm.vms.collectAsState()
    val entries by vm.entries.collectAsState()
    val engine by vm.engine.collectAsState()
    val runtime by VmRuntime.state.collectAsState()
    val picker = rememberLauncherForActivityResult(OpenDocs()) { vm.importUris(it) }
    val last = vms.maxByOrNull { it.lastStartedAt ?: it.createdAt }
    val recent = entries.filter { it.category in setOf(Category.EXE, Category.MSI, Category.APK) && it.lastUsedAt != null }
        .sortedByDescending { it.lastUsedAt }.take(5)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vantora", style = MaterialTheme.typography.headlineMedium)
                    Text("Your pocket computer", color = VColors.Muted)
                }
                IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
            }
        }
        item {
            Section("Virtual Computer") {
                if (last == null) {
                    Text("No virtual computer yet.")
                    Button(onClick = { onNavigate("computer") }) { Text("Set one up") }
                } else {
                    Text("${last.name} · ${last.guestArch} · ${last.ramMb} MB · ${last.cores} cores")
                    if (VmRuntime.isBusy) Button(onClick = { VmController.openDisplay(ctx) }) { Text("Open running session") }
                    else Button(onClick = { vm.startVm(last) }) { Text("Continue last session") }
                }
            }
        }
        item {
            Section("Quick access") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate("windows") }) { Text("Windows apps") }
                    Button(onClick = { onNavigate("android") }) { Text("Android apps") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import files") }
                    OutlinedButton(onClick = { onNavigate("settings") }) { Text("Device compatibility") }
                }
            }
        }
        item {
            Section("Engine status") {
                if (engine.isEmpty()) Text("Checking…", color = VColors.Muted)
                engine.filter { it.severity != Severity.INFO }.ifEmpty { engine.take(1) }.forEach { AdviceRow(it) }
            }
        }
        item { Text("Recent applications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (recent.isEmpty()) item { Text("Nothing launched yet.", color = VColors.Muted) }
        items(recent, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.title, Modifier.weight(1f)); Pill(Category.labels[e.category] ?: "", VColors.Violet)
            } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
