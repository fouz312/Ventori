package com.vantora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vantora.app.compat.CompatStatus
import com.vantora.app.core.Category
import com.vantora.app.core.LibraryEntry
import com.vantora.app.library.LibraryMeta

@Composable
fun WindowsScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsState()
    val caps by vm.caps.collectAsState()
    val apps = entries.filter { it.category == Category.EXE || it.category == Category.MSI }
    val picker = rememberLauncherForActivityResult(OpenDocs()) { vm.importUris(it) }
    var detail by remember { mutableStateOf<LibraryEntry?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Windows Applications", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp)) }
        item { Text("EXE/MSI files are analysed (CPU architecture, imports) and matched to an execution backend. Nothing is claimed to work until a backend actually runs it.", style = MaterialTheme.typography.bodySmall, color = VColors.Muted) }
        item { Button(onClick = { picker.launch(arrayOf("application/x-msdownload", "application/x-msi", "application/octet-stream", "*/*")) }) { Text("Import EXE / MSI") } }
        item { Section("Installed applications") { Text("None. Installing into a Wine prefix needs the Wine runtime, which is not implemented in stage 1.", style = MaterialTheme.typography.bodySmall) } }
        if (apps.isEmpty()) item { Empty("No imported Windows programs yet.") }
        items(apps, key = { it.id }) { e ->
            val w = LibraryMeta.win(e)
            val r = if (caps != null) vm.evaluate(e) else null
            Section(e.title) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (w.isMsi) "MSI" else w.machine.name, VColors.Violet)
                    r?.let {
                        Pill(it.status.label, when (it.status) { CompatStatus.EXPERIMENTAL -> VColors.Ok; CompatStatus.BACKEND_MISSING -> VColors.Warn; CompatStatus.UNSUPPORTED -> VColors.Bad; else -> VColors.Muted })
                    }
                }
                Text(r?.summary ?: "Analysing device…", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.launchWindows(e) }) { Text("Launch") }
                    OutlinedButton(onClick = { detail = e }) { Text("Details / Configure") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    detail?.let { e ->
        val r = vm.evaluate(e)
        val w = LibraryMeta.win(e)
        var (a0, v0) = LibraryMeta.args(e)
        var args by remember { mutableStateOf(a0) }
        var env by remember { mutableStateOf(v0) }
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("Architecture", if (w.isMsi) "MSI package" else w.machine.name)
                KeyValue("Backend", vm.let { r?.backendId ?: "none" })
                r?.reasons?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (!r?.dependencies.isNullOrEmpty()) { Text("Runtime dependencies", style = MaterialTheme.typography.titleMedium); r!!.dependencies.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
                if (w.imports.isNotEmpty()) Text("Imports: " + w.imports.take(12).joinToString(", ") + if (w.imports.size > 12) "…" else "", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(args, { args = it }, label = { Text("Launch arguments") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(env, { env = it }, label = { Text("Environment (KEY=VALUE;…)") }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { vm.saveWinConfig(e, args, env); detail = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}
