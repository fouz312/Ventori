package com.vantora.app.compat

import com.vantora.app.device.DeviceCaps
import java.io.File

data class WinAppInfo(
    val fileName: String,
    val isMsi: Boolean,
    val machine: PeMachine,
    val subsystem: Int,
    val isDotNet: Boolean,
    val imports: List<String>,
    val tags: Set<String>
)

enum class CompatStatus(val label: String) {
    UNSUPPORTED("Unsupported"),
    BACKEND_MISSING("Runtime not installed"),
    EXPERIMENTAL("Experimental (unverified)"),
    UNKNOWN("Unknown")
}

data class CompatReport(
    val status: CompatStatus,
    val backendId: String?,
    val summary: String,
    val reasons: List<String>,
    val dependencies: List<String>
)

sealed interface LaunchResult {
    data class Started(val message: String) : LaunchResult
    data class NotAvailable(val reason: String) : LaunchResult
}

/** Each way of running Windows code is its own backend; they are NOT interchangeable. */
interface ExecutionBackend {
    val id: String
    val displayName: String
    fun isInstalled(): Boolean
    fun supports(app: WinAppInfo, caps: DeviceCaps): Boolean
    suspend fun launch(app: WinAppInfo, args: String, env: String): LaunchResult
}

/** Wine + Box64 (x86-64 → ARM64 translation of user-mode code). Needs a glibc userland, see docs. */
class WineBox64Backend(private val root: File) : ExecutionBackend {
    override val id = "wine-box64"
    override val displayName = "Wine + Box64 (x86-64 on ARM64)"
    private val dir get() = File(root, "wine-box64")
    override fun isInstalled() = File(dir, "bin/wine64").exists() && File(dir, "bin/box64").exists()
    override fun supports(app: WinAppInfo, caps: DeviceCaps) =
        caps.isArm64 && (app.machine == PeMachine.X64 || (app.machine == PeMachine.X86 && caps.has32BitArm))
    override suspend fun launch(app: WinAppInfo, args: String, env: String): LaunchResult =
        if (!isInstalled()) LaunchResult.NotAvailable("The Wine+Box64 runtime is not installed (expected in ${dir.path}). Runtime packaging is not implemented in stage 1.")
        else LaunchResult.NotAvailable("A runtime was found, but the process bridge that starts wine64 under box64 is not implemented in stage 1.")
}

/** Wine for ARM64 Windows binaries on an ARM64 device (no CPU translation required). */
class WineArm64Backend(private val root: File) : ExecutionBackend {
    override val id = "wine-arm64"
    override val displayName = "Wine ARM64 (native ARM64 PE)"
    private val dir get() = File(root, "wine-arm64")
    override fun isInstalled() = File(dir, "bin/wine").exists()
    override fun supports(app: WinAppInfo, caps: DeviceCaps) = caps.isArm64 && app.machine == PeMachine.ARM64
    override suspend fun launch(app: WinAppInfo, args: String, env: String): LaunchResult =
        LaunchResult.NotAvailable("Wine ARM64 runtime packaging is not implemented in stage 1.")
}

/** Runs the program inside a full Windows VM (needs guest file sharing / agent, not implemented). */
class QemuGuestBackend(private val hasVm: () -> Boolean) : ExecutionBackend {
    override val id = "qemu-guest"
    override val displayName = "Inside the Windows virtual computer"
    override fun isInstalled() = hasVm()
    override fun supports(app: WinAppInfo, caps: DeviceCaps) =
        app.machine != PeMachine.UNKNOWN || app.isMsi
    override suspend fun launch(app: WinAppInfo, args: String, env: String): LaunchResult =
        LaunchResult.NotAvailable("Boot the virtual computer and run the file from inside Windows. Automatic file sharing/launching into the guest is not implemented in stage 1.")
}

class CompatEngine(runtimeRoot: File, hasWindowsVm: () -> Boolean) {
    val backends: List<ExecutionBackend> = listOf(
        WineArm64Backend(runtimeRoot), WineBox64Backend(runtimeRoot), QemuGuestBackend(hasWindowsVm)
    )

    fun backend(id: String?) = backends.firstOrNull { it.id == id }

    fun dependenciesOf(app: WinAppInfo): List<String> {
        val d = linkedSetOf<String>()
        for (imp in app.imports) when {
            imp.startsWith("d3d12") -> d += "Direct3D 12 → VKD3D-Proton + Vulkan 1.1+"
            imp.startsWith("d3d11") || imp.startsWith("dxgi") -> d += "Direct3D 11 → DXVK + Vulkan 1.1+"
            imp.startsWith("d3d9") || imp.startsWith("d3d10") -> d += "Direct3D 9/10 → DXVK + Vulkan"
            imp.startsWith("vcruntime140") || imp.startsWith("msvcp140") -> d += "Microsoft Visual C++ 2015–2022 runtime"
            imp.startsWith("mscoree") -> d += ".NET runtime (wine-mono or .NET)"
            imp.startsWith("opengl32") -> d += "OpenGL (via Android GLES/ANGLE or Zink)"
            imp.startsWith("xinput") -> d += "XInput controller emulation"
        }
        if (app.isDotNet) d += ".NET runtime (wine-mono or .NET)"
        return d.toList()
    }

    fun evaluate(app: WinAppInfo, caps: DeviceCaps): CompatReport {
        val deps = dependenciesOf(app)

        // Known-profile: Roblox. Based on how the product is built; no bypass is attempted or offered.
        if ("roblox" in app.tags || app.fileName.contains("roblox", ignoreCase = true)) {
            return CompatReport(
                CompatStatus.UNSUPPORTED, null,
                "Roblox's Windows client is not expected to work here.",
                listOf(
                    "${app.fileName} is only a small bootstrapper: it downloads the real x64 Roblox Player. Seeing the installer window would NOT mean the game runs.",
                    "The Windows client is protected by Roblox's Hyperion anti-tamper/anti-cheat. As far as publicly known, it does not support Wine-style layers and may refuse to run in emulated/virtual machines. Vantora does not and will not try to bypass it.",
                    "The client needs Direct3D 11-class graphics. Phones have no Windows GPU driver; this would need DXVK on Vulkan, which anti-cheat cannot be assumed to accept.",
                    "Inside a TCG-emulated Windows VM the game would be unplayably slow even if it launched.",
                    "Realistic alternative: the official Roblox Android app, which this app can install and launch from the Android Library (APK) tab. Future Windows-client support depends on Roblox, not on this app."
                ), deps
            )
        }
        val anti = app.tags.intersect(setOf("easyanticheat", "battleye", "vanguard", "hyperion", "byfron"))
        val reasons = mutableListOf<String>()
        if (anti.isNotEmpty()) reasons += "Contains references to kernel anti-cheat (${anti.joinToString()}), which generally refuses to run on compatibility layers and emulators."

        if (app.machine == PeMachine.UNKNOWN && !app.isMsi)
            return CompatReport(CompatStatus.UNKNOWN, null, "Could not read a valid PE header.", reasons + "The file may be corrupt or not a Windows executable.", deps)

        val candidates = backends.filter { it.supports(app, caps) }
        if (candidates.isEmpty()) {
            val why = when {
                !caps.isArm64 -> "This build only targets ARM64 devices."
                app.machine == PeMachine.X86 && !caps.has32BitArm -> "32-bit x86 programs need 32-bit ARM support (Box86); this CPU/OS offers 64-bit ARM only."
                app.machine == PeMachine.ARM -> "32-bit ARM Windows programs are not supported."
                else -> "No backend supports ${app.machine}."
            }
            return CompatReport(CompatStatus.UNSUPPORTED, null, "No suitable backend.", reasons + why, deps)
        }
        // Prefer a direct Wine backend over running inside a VM.
        val pick = candidates.first()
        reasons += when (pick.id) {
            "wine-arm64" -> "ARM64 binary on an ARM64 device: no CPU translation needed."
            "wine-box64" -> "x86/x64 binary: Box64 translates CPU instructions, Wine translates Windows API calls. Performance and compatibility vary per application."
            else -> "Only the full Windows VM (QEMU) can run this; expect slow, software-emulated execution."
        }
        if (deps.any { it.contains("Direct3D") }) reasons += "Uses Direct3D, which needs a Vulkan driver on the phone."
        if (app.isMsi) reasons += "MSI packages are installed with msiexec inside the chosen runtime."
        val status = if (pick.isInstalled() && anti.isEmpty()) CompatStatus.EXPERIMENTAL
        else if (anti.isNotEmpty()) CompatStatus.UNSUPPORTED else CompatStatus.BACKEND_MISSING
        val summary = when (status) {
            CompatStatus.EXPERIMENTAL -> "May run via ${pick.displayName}; unverified."
            CompatStatus.BACKEND_MISSING -> "Would use ${pick.displayName}, but that runtime is not installed."
            else -> "Unsupported."
        }
        return CompatReport(status, pick.id, summary, reasons, deps)
    }
}
