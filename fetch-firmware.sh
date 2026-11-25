#!/usr/bin/env bash
# Downloads open-source UEFI firmware (edk2: BSD-2-Clause-Patent) from Debian/Ubuntu packages
# and places it into the app's assets. Run by CI; safe to run locally on Debian/Ubuntu.
set -euo pipefail
ROOT="$(pwd)"
OUT="${1:-$ROOT/app/src/main/assets/firmware}"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
cd "$TMP"
sudo apt-get update -qq
apt-get download qemu-efi-aarch64 ovmf
for d in *.deb; do dpkg -x "$d" x; done

cp x/usr/share/qemu-efi-aarch64/QEMU_EFI.fd "$OUT/QEMU_EFI.fd"

code="$(find x/usr/share/OVMF -name 'OVMF_CODE_4M.fd' | head -1)"
[ -z "$code" ] && code="$(find x/usr/share/OVMF -name 'OVMF_CODE.fd' | head -1)"
vars="$(find x/usr/share/OVMF -name 'OVMF_VARS_4M.fd' | head -1)"
[ -z "$vars" ] && vars="$(find x/usr/share/OVMF -name 'OVMF_VARS.fd' | head -1)"
[ -n "$code" ] && [ -n "$vars" ] || { echo "OVMF files not found"; exit 1; }
cp "$code" "$OUT/OVMF_CODE.fd"
cp "$vars" "$OUT/OVMF_VARS.fd"
cp x/usr/share/doc/qemu-efi-aarch64/copyright "$OUT/NOTICE-qemu-efi-aarch64.txt" 2>/dev/null || true
cp x/usr/share/doc/ovmf/copyright "$OUT/NOTICE-ovmf.txt" 2>/dev/null || true
echo "Firmware ready in $OUT:"; ls -l "$OUT"
