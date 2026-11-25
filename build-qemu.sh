#!/usr/bin/env bash
# Cross-compiles QEMU (system emulators) for Android arm64 with the NDK, linking its dependencies statically.
#
# STATUS: written from the upstream build systems' documented cross-compilation options but NOT yet run.
# Expect to iterate: bionic lacks a few APIs QEMU assumes (e.g. shm_open, some pthread bits). Put fixes as
# *.patch files in native/qemu/patches/ - they are applied automatically in alphabetical order.
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK root}"
API=28                                   # bionic gained iconv in API 28, which GLib needs
TRIPLE=aarch64-linux-android
QEMU_VERSION="${QEMU_VERSION:-9.1.0}"
TARGETS="${QEMU_TARGETS:-aarch64-softmmu,x86_64-softmmu}"
ZLIB=1.3.1; FFI=3.4.6; PCRE2=10.44; GLIB=2.80.5; PIXMAN=0.43.4; SLIRP=4.8.0

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"; OUT="$HERE/out"; PREFIX="$WORK/prefix"; SRC="$WORK/src"
mkdir -p "$SRC" "$PREFIX" "$OUT/jniLibs/arm64-v8a"

TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TC/bin:$PATH"
export CC="${TRIPLE}${API}-clang" CXX="${TRIPLE}${API}-clang++"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
export CFLAGS="-fPIC -O2 -I$PREFIX/include" LDFLAGS="-L$PREFIX/lib"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH=""

python3 -m venv "$WORK/venv" && "$WORK/venv/bin/pip" install -q meson tomli packaging
export PATH="$WORK/venv/bin:$PATH"

fetch() {  # url dir
  local url="$1" dir="$2"
  [ -d "$SRC/$dir" ] && return 0
  curl -fsSL "$url" -o "$WORK/dl.tar" && mkdir -p "$SRC/$dir" && tar -xf "$WORK/dl.tar" -C "$SRC/$dir" --strip-components=1
}

cat > "$WORK/cross.ini" <<INI
[binaries]
c = '$TC/bin/${TRIPLE}${API}-clang'
cpp = '$TC/bin/${TRIPLE}${API}-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[built-in options]
c_args = ['-fPIC', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
default_library = 'static'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
INI

meson_build() {  # dir [extra meson args]
  local d="$1"; shift
  ( cd "$SRC/$d" && meson setup _b --cross-file "$WORK/cross.ini" --prefix "$PREFIX" --libdir lib --buildtype release "$@" \
    && ninja -C _b install )
}
autotools_build() {  # dir [extra configure args]
  local d="$1"; shift
  ( cd "$SRC/$d" && ./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static "$@" \
    && make -j"$(nproc)" && make install )
}

echo "== zlib";   fetch "https://github.com/madler/zlib/releases/download/v$ZLIB/zlib-$ZLIB.tar.gz" zlib
( cd "$SRC/zlib" && ./configure --prefix="$PREFIX" --static && make -j"$(nproc)" && make install )
echo "== libffi"; fetch "https://github.com/libffi/libffi/releases/download/v$FFI/libffi-$FFI.tar.gz" libffi
autotools_build libffi --disable-docs
echo "== pcre2";  fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2/pcre2-$PCRE2.tar.gz" pcre2
autotools_build pcre2 --disable-pcre2grep-libz --disable-pcre2grep-libbz2
echo "== glib";   fetch "https://download.gnome.org/sources/glib/${GLIB%.*}/glib-$GLIB.tar.xz" glib
meson_build glib -Dtests=false -Dnls=disabled -Dlibmount=disabled -Dselinux=disabled -Dxattr=false \
  -Dglib_debug=disabled -Dintrospection=disabled -Dman-pages=disabled -Ddocumentation=false -Dsysprof=disabled
echo "== pixman"; fetch "https://cairographics.org/releases/pixman-$PIXMAN.tar.gz" pixman
meson_build pixman -Dtests=disabled -Ddemos=disabled -Dlibpng=disabled -Dgtk=disabled -Dopenmp=disabled
echo "== libslirp"; fetch "https://gitlab.com/qemu-project/libslirp/-/archive/v$SLIRP/libslirp-v$SLIRP.tar.gz" libslirp
meson_build libslirp

echo "== qemu $QEMU_VERSION"
if [ ! -d "$SRC/qemu" ]; then
  curl -fsSL "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" -o "$WORK/qemu.tar.xz"
  mkdir -p "$SRC/qemu" && tar -xf "$WORK/qemu.tar.xz" -C "$SRC/qemu" --strip-components=1
  for p in "$HERE"/patches/*.patch; do [ -e "$p" ] && ( cd "$SRC/qemu" && patch -p1 < "$p" ); done
fi
mkdir -p "$SRC/qemu/_build" && cd "$SRC/qemu/_build"
../configure \
  --cross-prefix="${TRIPLE}-" --cc="$CC" --cxx="$CXX" --host-cc=gcc \
  --target-list="$TARGETS" --enable-slirp --enable-tools \
  --disable-docs --disable-guest-agent --disable-werror --disable-user --disable-linux-user --disable-bsd-user \
  --disable-gtk --disable-sdl --disable-opengl --disable-curses --disable-libusb --disable-vde --disable-brlapi \
  --disable-alsa --disable-pa --disable-jack --disable-oss --disable-gnutls --disable-nettle --disable-gcrypt \
  --disable-libnfs --disable-rbd --disable-glusterfs --disable-libssh --disable-capstone --disable-fdt \
  --extra-cflags="$CFLAGS" --extra-ldflags="$LDFLAGS"
make -j"$(nproc)"

cp qemu-system-aarch64 "$OUT/jniLibs/arm64-v8a/libqemu-system-aarch64.so" 2>/dev/null || true
cp qemu-system-x86_64  "$OUT/jniLibs/arm64-v8a/libqemu-system-x86_64.so"  2>/dev/null || true
cp qemu-img            "$OUT/jniLibs/arm64-v8a/libqemu-img.so"            2>/dev/null || true
llvm-strip "$OUT"/jniLibs/arm64-v8a/*.so || true
ls -l "$OUT/jniLibs/arm64-v8a"
test -n "$(ls "$OUT"/jniLibs/arm64-v8a/libqemu-system-*.so 2>/dev/null)" || { echo "No QEMU binary produced"; exit 1; }
