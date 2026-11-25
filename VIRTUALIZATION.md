# Virtualization architecture and honest limitations

## Three separate systems
1. **Windows virtual computer** – QEMU runs a full machine and boots *your* Windows ISO.
2. **Windows application compatibility** – separate backends (`WineArm64Backend`, `WineBox64Backend`, `QemuGuestBackend`) chosen per program from its real PE header. They are not interchangeable.
3. **Android APKs** – installed by Android's `PackageInstaller`; unrelated to the other two.

## How the VM engine works
```
Compose UI ──► VmController ──► VmService (foreground) ──► NativeBridge.spawn (JNI fork/exec)
                                                               │  inherits install-media fd
                                                               ▼
                                          libqemu-system-aarch64.so  (runs from nativeLibraryDir)
                                               │ -vnc unix:run/vm-N.vnc      │ -qmp unix:run/vm-N.qmp
                                               ▼                             ▼
                              VncClient (RFB 3.8, Raw) ──► VmDisplayView   Qmp (power / reset)
```
* QEMU is an executable packaged as `lib*.so` so Android extracts it to `nativeLibraryDir`; that is the only place a normal app may execute code from on modern Android (`extractNativeLibs=true`, `useLegacyPackaging=true`).
* The ISO is opened through the Storage Access Framework and handed to QEMU as `/proc/self/fd/N` (the descriptor is inherited by the child process), so a 5 GB ISO is **not copied**. Non-seekable providers fall back to a copy into VM storage.
* Hardware presented to Windows uses only **inbox Windows drivers**: NVMe disk, xHCI USB keyboard + tablet, USB mass-storage CD, Intel e1000e NIC, ramfb (ARM64) / std VGA (x64). VirtIO is deliberately not used for installation because Windows has no inbox VirtIO drivers.
* UEFI: `QEMU_EFI.fd` (ARM64, padded to the 64 MiB pflash size) and `OVMF` (x64).
* Display comes from the guest's real framebuffer through VNC over a private Unix socket.

## What is and is not possible
| Topic | Reality |
|---|---|
| Hardware acceleration | Android apps cannot open `/dev/kvm` on normal devices. The engine runs in TCG (software) mode. `NativeBridge.nKvm()` probes it and the UI says so. |
| Windows ARM64 guest on ARM64 phone | Same ISA, but without KVM it is still emulated by TCG: expect a multi-hour install and a slow desktop. |
| Windows x64 guest on ARM64 phone | Full CPU emulation, several times slower again. |
| Windows 11 requirements | TPM 2.0 / Secure Boot are not provided in stage 1. Windows 10 media avoids this. |
| Graphics | No GPU passthrough or 3D acceleration; DirectX games will not run in the VM. |
| Wine + Box64 | Needs a glibc userland. Android blocks executing downloaded binaries from app data (targetSdk ≥ 29), so a runtime must be shipped inside the APK or run through a rooted/proot-style setup. **Not implemented in stage 1**; the backend classes and detection logic exist, `launch()` reports why it cannot run. |
| Anti-cheat | Never bypassed. Titles with kernel anti-cheat (e.g. Roblox Hyperion) are reported unsupported. |
| Android versions | Bundled engine is built for API 28+, arm64-v8a only. |

## Building the engine
`native/qemu/build-qemu.sh` cross-compiles zlib, libffi, PCRE2, GLib, pixman, libslirp and QEMU with the NDK. It has **not been executed yet**; first CI runs will likely need patches for bionic differences (drop them in `native/qemu/patches/`). The `app` job always builds, with or without the engine.
