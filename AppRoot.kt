package com.vantora.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("computer", "Computer", Icons.Default.Computer),
    Tab("windows", "Windows", Icons.Default.DesktopWindows),
    Tab("android", "Android", Icons.Default.Android),
    Tab("files", "Files", Icons.Default.Folder)
)

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (route != null && route != "wizard") NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = { go(nav, t.route) },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = if (vm.wizardDone) "home" else "wizard", modifier = Modifier.padding(pad)) {
            composable("wizard") { WizardScreen(vm) { vm.finishWizard(); go(nav, "home") } }
            composable("home") { HomeScreen(vm, onNavigate = { go(nav, it) }) }
            composable("computer") { ComputerScreen(vm, onCreate = { nav.navigate("wizard") }) }
            composable("windows") { WindowsScreen(vm) }
            composable("android") { AndroidScreen(vm) }
            composable("files") { FilesScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

private fun go(nav: NavHostController, route: String) = nav.navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}
