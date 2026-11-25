package com.vantora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vantora.app.core.Category
import com.vantora.app.core.LibraryEntry

@Composable
fun FilesScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsState()
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<LibraryEntry?>(null) }
    var reconnecting by remember { mutableStateOf<LibraryEntry?>(null) }
    val importer = rememberLauncherForActivityResult(OpenDocs()) { vm.importUris(it) }
    val reconnector = rememberLauncherForActivityResult(OpenDocs()) { uris ->
        val e = reconnecting; val u = uris.firstOrNull()
        if (e != null && u != null) vm.reconnect(e, u); reconnecting = null
    }

    val shown = entries.asSequence()
        .filter { query.isBlank() || it.title.contains(query, true) || it.displayName.contains(query, true) }
        .filter {
            when (filter) {
                "ALL" -> true; "FAV" -> it.favorite; "RECENT" -> it.lastUsedAt != null; else -> it.category == filter
            }
        }
        .let { if (filter == "RECENT") it.sortedByDescending { e -> e.lastUsedAt } else it }
        .toList()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("File Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Only references are stored. Removing an entry never deletes your file.", style = MaterialTheme.typography.bodySmall, color = VColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Add files") }
            OutlinedButton(onClick = { vm.rescan() }) { Text("Rescan") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter == "ALL", { filter = "ALL" }, { Text("All") })
            FilterChip(filter == "RECENT", { filter = "RECENT" }, { Text("Recent") })
            FilterChip(filter == "FAV", { filter = "FAV" }, { Text("Favorites") })
            Category.labels.forEach { (k, v) -> FilterChip(filter == k, { filter = k }, { Text(v) }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shown.isEmpty()) item { Empty("Nothing here.") }
            items(shown, key = { it.id }) { e ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.toggleFavorite(e) }) { Icon(if (e.favorite) Icons.Default.Star else Icons.Outlined.StarBorder, "Favorite") }
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium)
                            Text("${Category.labels[e.category]} · ${formatBytes(e.sizeBytes)}" + if (e.customName != null) " · ${e.displayName}" else "", style = MaterialTheme.typography.bodySmall, color = VColors.Muted)
                            if (e.missing) Pill("Missing – access lost", VColors.Bad)
                        }
                        if (e.missing) TextButton(onClick = { reconnecting = e; reconnector.launch(arrayOf("*/*")) }) { Text("Reconnect") }
                        Box {
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = e })
                                DropdownMenuItem({ Text("Reconnect / replace file") }, { menu = false; reconnecting = e; reconnector.launch(arrayOf("*/*")) })
                                DropdownMenuItem({ Text("Remove from library") }, { menu = false; vm.remove(e) })
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { e ->
        var n by remember { mutableStateOf(e.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename library entry") },
            text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(e, n); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}
