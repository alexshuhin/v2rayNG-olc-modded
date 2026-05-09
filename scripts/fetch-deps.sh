#!/usr/bin/env bash
# Fetches/builds the binary deps that are gitignored:
#   V2rayNG/app/libs/libv2ray.aar             (downloaded from 2dust/AndroidLibXrayLite release)
#   V2rayNG/app/libs/<abi>/libhev-socks5-tunnel.so   (built from ./hev-socks5-tunnel)
#   V2rayNG/app/src/main/jniLibs/<abi>/libolcrtc.so  (built from ./olcrtc as Android binary)
#
# Required env:
#   ANDROID_NDK_HOME       — path to Android NDK r29+
#   ANDROID_HOME           — Android SDK with platform-android-24 and build-tools
#   GOPATH (or default)    — needs `go` 1.26+ and `mage` in PATH
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_LIBS="$ROOT/V2rayNG/app/libs"
APP_JNI="$ROOT/V2rayNG/app/src/main/jniLibs"

LIBV2RAY_TAG="${LIBV2RAY_TAG:-v26.5.3}"
LIBV2RAY_URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/${LIBV2RAY_TAG}/libv2ray.aar"

ABI="${ABI:-arm64-v8a}"   # also supported below: armeabi-v7a, x86_64, x86

require_cmd() { command -v "$1" >/dev/null || { echo "Missing tool: $1" >&2; exit 1; }; }
require_env() { [ -n "${!1:-}" ] || { echo "Missing env: $1" >&2; exit 1; }; }

main() {
  require_env ANDROID_NDK_HOME
  require_env ANDROID_HOME
  require_cmd go
  require_cmd mage
  require_cmd curl

  mkdir -p "$APP_LIBS"
  mkdir -p "$APP_JNI/$ABI"

  fetch_libv2ray
  build_olcrtc_aar
  build_olcrtc_binary
  build_hev_tunnel
}

fetch_libv2ray() {
  local out="$APP_LIBS/libv2ray.aar"
  if [ -f "$out" ]; then
    echo "[skip] libv2ray.aar already present ($(du -h "$out" | cut -f1))"
    return
  fi
  echo "[fetch] libv2ray.aar from $LIBV2RAY_URL"
  curl -fsSL -o "$out" "$LIBV2RAY_URL"
}

build_olcrtc_aar() {
  local out="$APP_LIBS/olcrtc.aar"
  echo "[build] olcrtc.aar (gomobile bind, javapkg=xyz.olcrtc)"
  ( cd "$ROOT/olcrtc" && \
      gomobile bind -target=android -androidapi 24 \
        -javapkg=xyz.olcrtc \
        -ldflags='-s -w -checklinkname=0' \
        -o build/olcrtc.aar ./mobile )

  # The runtime classes (`go.Seq*`, `go.Universe`, `go.error`) clash with the
  # ones inside libv2ray.aar (both AARs ship a gomobile runtime).
  # Strip them from olcrtc.aar; libv2ray's copy stays as the single source.
  echo "[strip] removing go.* runtime from olcrtc.aar to avoid duplicate classes"
  local tmp; tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  unzip -q "$ROOT/olcrtc/build/olcrtc.aar" -d "$tmp"
  ( cd "$tmp" && mkdir _jar && cd _jar && jar xf ../classes.jar && rm -rf go && jar cf ../classes.jar . )
  ( cd "$tmp" && rm -rf _jar && jar cf "$out" . )
  echo "[ok] $out ($(du -h "$out" | cut -f1))"
}

build_olcrtc_binary() {
  local cc out
  case "$ABI" in
    arm64-v8a)    cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"; export GOARCH=arm64 ;;
    armeabi-v7a) cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi24-clang"; export GOARCH=arm GOARM=7 ;;
    x86_64)       cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android24-clang"; export GOARCH=amd64 ;;
    x86)          cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-linux-android24-clang"; export GOARCH=386 ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
  esac
  out="$APP_JNI/$ABI/libolcrtc.so"
  echo "[build] $out (Android binary, GOARCH=$GOARCH)"
  ( cd "$ROOT/olcrtc" && \
      GOOS=android CGO_ENABLED=1 CC="$cc" \
      go build -trimpath -ldflags='-s -w -checklinkname=0' \
        -o "$out" ./cmd/olcrtc )
  chmod +x "$out"
  echo "[ok] $out ($(du -h "$out" | cut -f1))"
}

build_hev_tunnel() {
  local lib="$APP_LIBS/$ABI/libhev-socks5-tunnel.so"
  if [ -f "$lib" ]; then
    echo "[skip] $lib already present"
    return
  fi
  echo "[build] hev-socks5-tunnel for all ABIs (ndk-build)"
  ( cd "$ROOT" && NDK_HOME="$ANDROID_NDK_HOME" bash ./compile-hevtun.sh )
  # compile-hevtun.sh writes into $ROOT/libs/<abi>/libhev-socks5-tunnel.so;
  # mirror everything into V2rayNG/app/libs so AGP picks it up via jniLibs.srcDirs("libs").
  cp -r "$ROOT/libs/." "$APP_LIBS/"
  echo "[ok] $(ls "$APP_LIBS/$ABI/libhev-socks5-tunnel.so")"
}

main "$@"
