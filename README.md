# Vantora — your pocket computer

Android app that creates a **real virtual computer** (QEMU) from Windows installation media you provide, keeps a persistent
library of your files, analyses Windows EXE/MSI programs for compatibility, and separately installs/launches Android APKs.

**Stage 1 (this repository).** Read the status table before trusting any feature.

## Status — nothing below has been compiled or run yet
The code was written in an environment with no Kotlin compiler, Gradle, Android SDK/NDK or device. Static checks
(XML/YAML well-formedness, bracket balance, package/import cross-reference) pass; that is all that has been verified.
**The first GitHub Actions run is the first compile.** Expect a few compile errors to fix.

| Area | State |
|---|---|
| Setup wizard, navigation, theme, logo/icon/splash | Implemented, untested |
| Device probe (CPU, RAM, storage, GL via EGL, Vulkan feature, /dev/kvm) | Implemented, untested |
| Feasibility warnings, safe RAM/core limits | Implemented; logic covered by unit tests (not yet run) |
| ISO/VHD/VHDX/qcow2/VMDK/raw inspection + ISO architecture heuristic | Implemented; unit tests written (not yet run) |
| SAF library: persistable URIs, recents, favorites, search, categories, rescan, reconnect, rename, remove | Implemented, untested |
| VM config + sparse disk + UEFI firmware prep (Room) | Implemented, untested |
| JNI fork/exec with inherited fd, QMP, RFB client, display view, touch/mouse/keyboard, soft keyboard | Implemented, untested |
| **QEMU engine binary (cross-compiled in CI)** | **Partially implemented**: build script written, never run; likely needs bionic patches |
| Windows boot/installation | **Unverified.** Depends on the engine building and running. Software emulation only: very slow |
| PE analysis, compatibility engine, Roblox verdict, dependency hints | Implemented; unit tests written (not yet run) |
| Wine / Box64 runtimes and launching EXE/MSI | **Not implemented** (backends report why launch is unavailable) |
| Android APK import/inspect/install (PackageInstaller)/open/manage/shortcut | Implemented, untested. Split bundles unsupported |
| Clipboard sync (RFB cut-text transport) | Partial: protocol handled, no UI wiring and needs a guest agent |
| File sharing with the guest, game controllers, guest resolution control, virtio-gpu, TPM/Secure Boot, hardware KVM | Not implemented |
| Update checker (GitHub releases API) | Implemented, untested |
| Gradle modules | Packages mirror the 14 requested modules inside one Gradle module for build reliability; split later |

Details and limits: `docs/VIRTUALIZATION.md`. Licences: `THIRD_PARTY.md`. Design: `docs/DESIGN.md`.

## Build with GitHub Actions (no Android Studio needed)
See `docs/GITHUB_GUIDE.md`. Short version: upload the project → **Actions → Android Build → Run workflow** → download the
`vantora-debug-apk` artifact.

## Rules this project follows
No fake emulator or fake desktop (the display is the guest framebuffer), no bundled Windows images (`.gitignore` blocks
`*.iso`), no anti-cheat bypass, no claim that an app works unless a backend actually ran it.
