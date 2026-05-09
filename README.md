# v2rayNG-olc-modded

Fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG) with first-class support for the
[olcRTC](https://github.com/openlibrecommunity/olcrtc) protocol — a tunnel that hides traffic
inside legitimate WebRTC video services (Yandex Telemost, VK Звонки, Wildberries Stream).

The original v2rayNG functionality (VMess / VLESS / Shadowsocks / Trojan / Wireguard / Hysteria2)
is untouched; this fork only adds an extra protocol type and the wiring needed to run its
WebRTC engine alongside libv2ray.

> All changes live on the `olc-modded` branch. The `master` branch is kept identical to
> upstream `2dust/v2rayNG` so it can be re-synced with `git pull upstream master` cleanly.
> The original upstream README is preserved as [`UPSTREAM-README.md`](./UPSTREAM-README.md).

## Why

Carrier whitelists in some networks pin a small set of "safe" services and drop everything else.
olcRTC enrolls the client and a remote server as two participants in a real WebRTC call on a
whitelisted SFU and shovels arbitrary TCP through that data channel. Blocking it requires
blocking the underlying video service, which is not realistic for traffic-bearing whitelists.

Read the protocol description here: <https://habr.com/ru/articles/1020114/>

## What's new vs upstream

- **`olcrtc://` URI scheme + import** in `AngConfigManager` and `UrlSchemeActivity`
- **`Add [olcRTC]` menu entry** with carrier/transport/room/client/key form
- **`OlcrtcEngineController`** runs the olcrtc CLI as a child process (the gomobile AAR
  conflicts with libv2ray's `libgojni.so`, so a separate process is the only way to coexist)
- **`mapdns` enabled in hev-socks5-tunnel** for OLCRTC profiles. olcrtc only does TCP, so
  hev hands out fake IPs from `100.64.0.0/10` and rewrites incoming connects into hostname-
  based SOCKS5 CONNECT — the remote olcrtc server resolves DNS itself
- **VPN routes for the fake-DNS range** (`198.18.0.0/15`, `100.64.0.0/10`) added to the
  tun configuration when an OLCRTC profile is active
- **Process-aware `isRunning()`** — the QSTile in the status bar now lights up correctly
  when the olcrtc engine is the active backend

Files touched:
```
V2rayNG/app/build.gradle.kts                          (buildToolsVersion + version suffix)
V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt
V2rayNG/app/src/main/java/com/v2ray/ang/enums/EConfigType.kt
V2rayNG/app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt
V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt
V2rayNG/app/src/main/java/com/v2ray/ang/ui/ServerActivity.kt
V2rayNG/app/src/main/java/com/v2ray/ang/ui/UrlSchemeActivity.kt
V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt
V2rayNG/app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt
V2rayNG/app/src/main/java/com/v2ray/ang/service/TProxyService.kt
V2rayNG/app/src/main/res/menu/menu_main.xml
V2rayNG/app/src/main/res/values/strings.xml
V2rayNG/app/src/main/java/com/v2ray/ang/fmt/OlcrtcFmt.kt                 (new)
V2rayNG/app/src/main/java/com/v2ray/ang/core/OlcrtcEngineController.kt    (new)
V2rayNG/app/src/main/res/layout/activity_server_olcrtc.xml                (new)
```

## Build

You need Android NDK r29+, Android SDK with `platform-android-24` and `build-tools;37.0.0`,
JDK 17+, Go 1.26+ with `gomobile`, and `mage`.

```bash
git clone --recurse-submodules -b olc-modded https://github.com/alexshuhin/v2rayNG-olc-modded
cd v2rayNG-olc-modded
export ANDROID_NDK_HOME=/path/to/android-ndk
export ANDROID_HOME=/path/to/android-sdk
ABI=arm64-v8a ./scripts/fetch-deps.sh        # downloads libv2ray.aar + builds olcrtc + hev
cd V2rayNG && ./gradlew :app:assembleFdroidDebug
```

`fetch-deps.sh` populates the gitignored binary artefacts:

| File | Source |
|---|---|
| `V2rayNG/app/libs/libv2ray.aar` | [`2dust/AndroidLibXrayLite`](https://github.com/2dust/AndroidLibXrayLite/releases) (prebuilt release) |
| `V2rayNG/app/libs/<abi>/libhev-socks5-tunnel.so` | built from `./hev-socks5-tunnel` (vendored submodule, upstream from v2rayNG) |
| `V2rayNG/app/src/main/jniLibs/<abi>/libolcrtc.so` | built from `./olcrtc` submodule (`go build` for `GOOS=android`) |
| `V2rayNG/app/libs/olcrtc.aar` | built from `./olcrtc/mobile` via `gomobile bind`, with `go.*` runtime stripped |

Other ABIs: rerun `ABI=armeabi-v7a ./scripts/fetch-deps.sh` etc.

## Usage

1. On a server (any Linux VPS outside the restricted network):
   ```bash
   olcrtc -mode srv -carrier wbstream -transport datachannel \
     -id any -client-id default -key $(openssl rand -hex 32) \
     -link direct -data data -dns 1.1.1.1:53
   ```
   Watch the logs for the room ID. The combo of `(room id, client id, key, carrier, transport)`
   is the credentials the client needs.

2. In the app: **+** → **Add [olcRTC]**, fill in the five fields, save, tap V at the bottom.

3. For the URI form (paste / share / QR), see [olcrtc/docs/uri.md](olcrtc/docs/uri.md).

## Credits

- [2dust](https://github.com/2dust) — original v2rayNG / AndroidLibXrayLite
- [openlibrecommunity](https://github.com/openlibrecommunity) / [zarazaex](https://t.me/zarazaexe) — olcRTC
- [heiher](https://github.com/heiher) — hev-socks5-tunnel

## License

GPL-3.0, inherited from upstream v2rayNG.
