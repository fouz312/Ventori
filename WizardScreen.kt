package com.vantora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vantora.app.R
import com.vantora.app.core.GuestArch
import com.vantora.app.core.LibraryEntry
import com.vantora.app.core.VmEntity
import com.vantora.app.device.Feasibility
import com.vantora.app.library.LibraryMeta

private val resolutions = listOf(1024 to 768, 1280 to 720, 1280 to 800, 1920 to 1080)

@Composable
fun WizardScreen(vm: AppViewModel, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val caps by vm.caps.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var media by remember { mutableStateOf<LibraryEntry?>(null) }
    var importing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Windows PC") }
    var arch by remember { mutableStateOf(GuestArch.ARM64) }
    var ram by remember { mutableIntStateOf(4096) }
    var cores by remember { mutableIntStateOf(2) }
    var disk by remember { mutableIntStateOf(64) }
    var resIdx by remember { mutableIntStateOf(0) }
    var net by remember { mutableStateOf(true) }
    var created by remember { mutableStateOf<VmEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(OpenDocs()) { uris ->
        uris.firstOrNull()?.let { u ->
            importing = true
            vm.importOne(u) { e ->
                importing = false; media = e
                e?.let { m -> LibraryMeta.media(m).arch.takeIf { it == GuestArch.ARM64 || it == GuestArch.X64 }?.let { arch = it } }
            }
        }
    }

    val c = caps
    val maxRam = c?.let { Feasibility.maxRamMb(it) } ?: 4096
    val maxCores = c?.let { Feasibility.maxCores(it) } ?: 2

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Step ${step + 1} of 6", color = VColors.Muted)
        LinearProgressIndicator(progress = { (step + 1) / 6f }, modifier = Modifier.fillMaxWidth())

        when (step) {
            0 -> {
                LogoMark(96.dp, Modifier.align(Alignment.CenterHorizontally))
                Text("Welcome to Vantora", style = MaterialTheme.typography.headlineMedium)
                Text("Vantora turns your phone into a virtual computer. It boots Windows installation media you provide inside a real virtual machine (QEMU), keeps a library of your files, and separately installs Android APKs with Android's own installer.")
                Section("Honest expectations") {
                    Text("• Phones cannot use hardware virtualization from a normal app, so Windows runs by software emulation and is slow.", style = MaterialTheme.typography.bodySmall)
                    Text("• You supply your own Windows ISO and licence. Nothing from Microsoft is bundled.", style = MaterialTheme.typography.bodySmall)
                    Text("• Windows programs, Android apps and the virtual computer are three separate systems.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onFinish) { Text("Skip setup") }
            }
            1 -> {
                Text("Select Windows installation media", style = MaterialTheme.typography.titleLarge)
                Text("An ISO is installation media, not an installed Windows. Vantora will attach it to a new virtual computer and boot it so you can install.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) { Text(if (media == null) "Choose ISO / IMG / VHD / VHDX…" else "Choose a different file…") }
                if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Inspecting the image…", style = MaterialTheme.typography.bodySmall) }
                media?.let { m ->
                    val s = LibraryMeta.media(m)
                    Section(m.title) {
                        KeyValue("Format", s.format); KeyValue("Architecture", s.arch.name)
                        KeyValue("Volume label", s.label ?: "—"); KeyValue("Size", formatBytes(m.sizeBytes))
                        s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            2 -> {
                Text("Device compatibility", style = MaterialTheme.typography.titleLarge)
                if (c == null) CircularProgressIndicator() else {
                    Section("This device") {
                        KeyValue("Device", c.device); KeyValue("Android", "${c.androidRelease} (API ${c.androidSdk})")
                        KeyValue("CPU", "${c.cpuModel} · ${c.cpuCores} cores · ${c.abis.firstOrNull()}")
                        KeyValue("RAM", "${c.totalRamMb} MB total, ${c.availRamMb} MB free")
                        KeyValue("Free storage", "${c.freeStorageMb / 1024} GB")
                        KeyValue("GPU", c.glRenderer); KeyValue("OpenGL ES", c.glEs); KeyValue("Vulkan", c.vulkanApi ?: "not reported")
                        KeyValue("/dev/kvm", if (c.kvmAccessible) "accessible" else "not accessible")
                    }
                    Section("What is realistically possible") {
                        Feasibility.assess(c, arch, ram, cores, disk, vm.engineBundled(arch)).forEach { AdviceRow(it) }
                    }
                }
            }
            3 -> {
                Text("Configure the virtual computer", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Guest architecture")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(arch == GuestArch.ARM64, { arch = GuestArch.ARM64 }, { Text("Windows ARM64") })
                    FilterChip(arch == GuestArch.X64, { arch = GuestArch.X64 }, { Text("Windows x64") })
                }
                Text("RAM: $ram MB (safe max ${maxRam})")
                Slider(ram.toFloat(), { ram = (it / 256).toInt() * 256 }, valueRange = 2048f..maxOf(2048f, maxRam.toFloat()))
                Text("CPU cores: $cores (max $maxCores)")
                Slider(cores.toFloat(), { cores = it.toInt() }, valueRange = 1f..maxOf(1f, maxCores.toFloat()), steps = (maxCores - 2).coerceAtLeast(0))
                Text("Virtual disk: $disk GB (sparse file)")
                Slider(disk.toFloat(), { disk = (it / 8).toInt() * 8 }, valueRange = 32f..256f)
                Text("Preferred resolution")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolutions.forEachIndexed { i, r -> FilterChip(resIdx == i, { resIdx = i }, { Text("${r.first}×${r.second}") }) }
                }
                Text("The guest resolution is chosen by the virtual graphics firmware; this value is stored for the upcoming virtio-gpu stage. The viewer always scales to your screen.", style = MaterialTheme.typography.bodySmall, color = VColors.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(net, { net = it }); Spacer(Modifier.width(8.dp)); Text("Networking (user-mode NAT)") }
                c?.let { cc -> Feasibility.assess(cc, arch, ram, cores, disk, vm.engineBundled(arch)).filter { it.severity != com.vantora.app.device.Severity.INFO }.forEach { AdviceRow(it) } }
            }
            4 -> {
                Text("Create the virtual computer", style = MaterialTheme.typography.titleLarge)
                if (created == null) {
                    Text("This creates a sparse $disk GB virtual disk and saves the configuration. The install media stays where it is; only its reference is stored.")
                    Button(onClick = {
                        busy = true; err = null
                        vm.createVm(name, arch, ram, cores, disk, resolutions[resIdx].first, resolutions[resIdx].second, net, media?.id) { r ->
                            busy = false
                            r.onSuccess { created = it }.onFailure { err = it.message }
                        }
                    }, enabled = !busy && media != null) { Text("Create virtual computer") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    err?.let { Text(it, color = VColors.Bad) }
                } else {
                    Text("“${created!!.name}” was created.", color = VColors.Ok)
                    Button(onClick = { vm.startVm(created!!) }) { Text("Boot now and open the display") }
                    Text("If the engine is not bundled in this APK, the viewer will tell you exactly what is missing. Windows setup may wait for a keypress (“Press any key to boot from CD”): use the Keyboard button in the viewer.", style = MaterialTheme.typography.bodySmall)
                }
            }
            5 -> {
                Text("Everything is saved", style = MaterialTheme.typography.titleLarge)
                Section("Saved") {
                    Text("• Virtual machine configuration and disk location")
                    Text("• A reference to your install media (reconnect it from Files if Android revokes access)")
                    Text("• Device settings and preferences")
                }
                Text("Resume your virtual computer any time from Home or the Computer tab.")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            if (step < 5) Button(onClick = { step++ }, enabled = !(step == 1 && media == null) && !(step == 4 && created == null)) { Text("Next") }
            else Button(onClick = onFinish) { Text("Finish") }
        }
    }
}
