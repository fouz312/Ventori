package com.vantora.app.vm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vantora.app.ui.VantoraTheme
import kotlinx.coroutines.delay
import java.io.File

/** Full-screen landscape viewer for the running virtual machine. Shows the REAL framebuffer, never a mock desktop. */
class VmDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { VantoraTheme { VmScreen(onExit = { finish() }) } }
    }
}

@Composable
private fun VmScreen(onExit: () -> Unit) {
    val state by VmRuntime.state.collectAsState()
    var view by remember { mutableStateOf<VmDisplayView?>(null) }
    var client by remember { mutableStateOf<VncClient?>(null) }
    var link by remember { mutableStateOf("Connecting to display…") }
    var reconnect by remember { mutableIntStateOf(0) }
    var bar by remember { mutableStateOf(true) }
    var keys by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(false) }
    var rightNext by remember { mutableStateOf(false) }
    var invert by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val mods = remember { mutableStateMapOf(0xFFE3 to false, 0xFFE9 to false, 0xFFE1 to false, 0xFFEB to false) }

    LaunchedEffect(state, reconnect) {
        val s = state
        client?.close(); client = null
        if (s is VmState.Running) {
            var waited = 0
            while (!File(s.vnc).exists() && waited < 120) { delay(500); waited++ }
            if (!File(s.vnc).exists()) { link = "Display socket did not appear. Check the engine log."; return@LaunchedEffect }
            val c = VncClient(s.vnc, object : VncClient.Listener {
                override fun onFrame() { view?.frameChanged(); link = "" }
                override fun onClipboard(text: String) { }
                override fun onClosed(reason: String?) { link = "Display disconnected: ${reason ?: "closed"}" }
            })
            client = c
            view?.client = c
            c.start()
        }
    }
    DisposableEffect(Unit) { onDispose { client?.close() } }
    LaunchedEffect(drag, rightNext, invert) { view?.dragMode = drag; view?.nextTapRight = rightNext; view?.invertScroll = invert }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> VmDisplayView(ctx).also { view = it; it.client = client } }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = state) {
                is VmState.Idle -> Text("Waiting for the virtual computer…", color = Color.White)
                is VmState.Starting -> { CircularProgressIndicator(); Text("Starting the QEMU engine…", color = Color.White) }
                is VmState.Running -> if (link.isNotEmpty()) {
                    Text(link, color = Color.White)
                    if (link.startsWith("Display")) Button(onClick = { reconnect++ }) { Text("Reconnect display") }
                }
                is VmState.Failed -> {
                    Text("Could not start", color = Color(0xFFFF8A80), style = MaterialTheme.typography.titleMedium)
                    Text(s.message, color = Color.White)
                }
                is VmState.Stopped -> {
                    Text("Virtual computer stopped (exit code ${s.exitCode})", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (s.logTail.isNotBlank()) Surface(color = Color(0xCC000000), modifier = Modifier.heightIn(max = 180.dp)) {
                        Text(s.logTail, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.background(Color(0xCC0E1020)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { bar = !bar }) { Text(if (bar) "▲" else "▼ Menu") }
                if (bar) {
                    TextButton(onClick = { view?.showKeyboard() }) { Text("Keyboard") }
                    TextButton(onClick = { keys = !keys }) { Text("Keys") }
                    FilterChip(selected = drag, onClick = { drag = !drag }, label = { Text("Drag") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = rightNext, onClick = { rightNext = !rightNext }, label = { Text("Right-click") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = invert, onClick = { invert = !invert }, label = { Text("Flip scroll") })
                    TextButton(onClick = { VmController.shutDown { info = if (it.contains("error")) it else "Shutdown requested; Windows will close itself." } }) { Text("Shut down") }
                    TextButton(onClick = { VmController.restart { info = "Reset sent." } }) { Text("Restart") }
                    TextButton(onClick = { VmController.forceStop(); info = "Force stopped." }) { Text("Force stop") }
                    TextButton(onClick = onExit) { Text("Close viewer") }
                }
            }
            if (bar && keys) {
                Row(Modifier.background(Color(0xCC171A30)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Ctrl" to 0xFFE3, "Alt" to 0xFFE9, "Shift" to 0xFFE1, "Win" to 0xFFEB).forEach { (n, sym) ->
                        FilterChip(selected = mods[sym] == true, onClick = {
                            val on = mods[sym] != true; mods[sym] = on; view?.sendKeysym(sym, on)
                        }, label = { Text(n) })
                        Spacer(Modifier.width(4.dp))
                    }
                    listOf("Esc" to 0xFF1B, "Tab" to 0xFF09, "Del" to 0xFFFF, "←" to 0xFF51, "↑" to 0xFF52, "↓" to 0xFF54, "→" to 0xFF53).forEach { (n, sym) ->
                        TextButton(onClick = { view?.tapKey(sym) }) { Text(n) }
                    }
                    TextButton(onClick = {
                        view?.let { v -> listOf(0xFFE3, 0xFFE9, 0xFFFF).forEach { v.sendKeysym(it, true) }; listOf(0xFFFF, 0xFFE9, 0xFFE3).forEach { v.sendKeysym(it, false) } }
                    }) { Text("Ctrl+Alt+Del") }
                    (1..12).forEach { f -> TextButton(onClick = { view?.tapKey(0xFFBE + f - 1) }) { Text("F$f") } }
                }
            }
            info?.let { Text(it, color = Color.White, modifier = Modifier.background(Color(0xAA000000)).padding(6.dp)) }
        }
    }
}
