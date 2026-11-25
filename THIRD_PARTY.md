# Third-party components

| Component | Use | License |
|---|---|---|
| QEMU 9.x | Virtual machine engine (built in CI, shipped as `libqemu-system-*.so`) | GPL-2.0-or-later (plus others per file) |
| edk2 / OVMF / QEMU_EFI | UEFI firmware (downloaded from Debian/Ubuntu packages in CI) | BSD-2-Clause-Patent (+ OpenSSL parts, Apache-2.0) |
| GLib, libffi, PCRE2, zlib, pixman, libslirp | QEMU dependencies (statically linked) | LGPL-2.1+, MIT, BSD, zlib, MIT, BSD-3 |
| AndroidX, Jetpack Compose, Room, Navigation, Kotlin, kotlinx.coroutines | App framework | Apache-2.0 |

**GPL note.** QEMU is GPL software. If you distribute an APK that bundles a QEMU binary you must offer the
corresponding source, including any patches in `native/qemu/patches/` and the build script. The upstream tarball URLs and
versions are pinned in `native/qemu/build-qemu.sh`.

**Not included / not distributed:** Windows, Roblox, Wine, Box64. Wine (LGPL-2.1+) and Box64 (MIT) are planned
backends (see docs/VIRTUALIZATION.md) and are not bundled in stage 1.
