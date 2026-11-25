package com.vantora.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vantora.app.core.GuestArch
import com.vantora.app.core.VmEntity
import com.vantora.app.device.Feasibility
import com.vantora.app.vm.VmController
import com.vantora.app.vm.VmRuntime
import com.vantora.app.vm.VmState

@Composable
fun ComputerScreen(vm: AppViewModel, onCreate: () -> Unit) {
    val ctx = LocalContext.current
    val vms by vm.vms.collectAsState()
    val caps by vm.caps.collectAsState()
    val state by VmRuntime.state.collectAsState()
    var editing by remember { mutableStateOf<VmEntity?>(null) }
    var deleting by remember { mutableStateOf<VmEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Virtual Computer", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp)) }
        item { Button(onClick = onCreate) { Text("Create virtual computer") } }
        if (vms.isEmpty()) item { Empty("No virtual computers yet. Use the setup wizard to create one from your Windows ISO.") }
        items(vms, key = { it.id }) { v ->
            val running = VmRuntime.vmId == v.id && state is VmState.Running
            val starting = VmRuntime.vmId == v.id && state is VmState.Starting
            Section(v.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(v.guestArch, VColors.Violet)
                    Pill(if (running) "Running" else if (starting) "Starting" else "Off", if (running) VColors.Ok else VColors.Muted)
                }
                KeyValue("RAM / CPU", "${v.ramMb} MB · ${v.cores} cores")
                KeyValue("Virtual disk", "${v.diskGb} GB (sparse)")
                KeyValue("Disk file", v.diskPath)
                KeyValue("Install media", if (v.isoEntryId != null) "attached" else "detached")
                KeyValue("Network", if (v.network) "NAT" else "off")
                val arch = if (v.guestArch == "ARM64") GuestArch.ARM64 else GuestArch.X64
                caps?.let { c -> Feasibility.assess(c, arch, v.ramMb, v.cores, v.diskGb, vm.engineBundled(arch)).filter { it.severity.ordinal > 0 }.forEach { AdviceRow(it) } }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (running || starting) Button(onClick = { VmController.openDisplay(ctx) }) { Text("Display") }
                    else Button(onClick = { vm.startVm(v) }) { Text("Start") }
                    OutlinedButton(onClick = { VmController.shutDown { vm.say("Shutdown requested.") } }, enabled = running) { Text("Shut down") }
                    OutlinedButton(onClick = { VmController.restart { vm.say("Reset sent.") } }, enabled = running) { Text("Restart") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editing = v }, enabled = !running) { Text("Settings") }
                    TextButton(onClick = { vm.detachMedia(v) }, enabled = v.isoEntryId != null && !running) { Text("Detach install media") }
                    TextButton(onClick = { VmController.forceStop() }, enabled = running) { Text("Force stop") }
                    TextButton(onClick = { deleting = v }, enabled = !running) { Text("Delete") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    editing?.let { v ->
        var ram by remember { mutableIntStateOf(v.ramMb) }
        var cores by remember { mutableIntStateOf(v.cores) }
        var disk by remember { mutableIntStateOf(v.diskGb) }
        var net by remember { mutableStateOf(v.network) }
        val maxRam = caps?.let { Feasibility.maxRamMb(it) } ?: 8192
        val maxCores = caps?.let { Feasibility.maxCores(it) } ?: 4
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Settings · ${v.name}") },
            text = { Column {
                Text("RAM $ram MB"); Slider(ram.toFloat(), { ram = (it / 256).toInt() * 256 }, valueRange = 2048f..maxOf(2048f, maxRam.toFloat(), v.ramMb.toFloat()))
                Text("Cores $cores"); Slider(cores.toFloat(), { cores = it.toInt() }, valueRange = 1f..maxOf(1f, maxCores.toFloat(), v.cores.toFloat()))
                Text("Disk $disk GB (can only grow; extend the partition inside Windows)"); Slider(disk.toFloat(), { disk = maxOf(v.diskGb, (it / 8).toInt() * 8) }, valueRange = v.diskGb.toFloat()..maxOf(v.diskGb + 8f, 512f))
                Row { Switch(net, { net = it }); Spacer(Modifier.width(8.dp)); Text("Networking") }
            } },
            confirmButton = { TextButton(onClick = { vm.saveVm(v, v.copy(ramMb = ram, cores = cores, diskGb = disk, network = net)); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    deleting?.let { v ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Delete “${v.name}”?") },
            text = { Text("This deletes the virtual disk, firmware variables and log of this virtual computer. Your ISO and other library files are not touched.") },
            confirmButton = { TextButton(onClick = { vm.deleteVm(v); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
