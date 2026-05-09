# Server-side: managing per-client olcrtc instances

A small CLI + systemd template to run one olcrtc server process per client
on a Linux VPS. olcrtc itself binds one server to one client (`-client-id`),
so each user gets their own instance, room, and key.

## Files

| File | Path on server | Purpose |
|---|---|---|
| `olcrtcctl` | `/usr/local/bin/olcrtcctl` | CLI: add / remove / list / show / logs / regen-room |
| `olcrtc@.service` | `/etc/systemd/system/olcrtc@.service` | Templated systemd unit (one instance per client) |

Per-client config lives in `/etc/olcrtc/<client-id>.env` (chmod 600 — contains the key).
State (currently nothing user-relevant) lives in `/var/lib/olcrtc`.

## Install

```bash
# Pre-req: olcrtc binary at /usr/local/bin/olcrtc and openssl in PATH.
# (Build from sources via `mage build` in the olcrtc repo, or scp the
# binary you already use locally.)
sudo install -m 755 olcrtcctl /usr/local/bin/olcrtcctl
sudo install -m 644 olcrtc@.service /etc/systemd/system/olcrtc@.service
sudo systemctl daemon-reload
# Optional: terminal QR rendering for `olcrtcctl show`
sudo apt install -y qrencode  # or dnf/pacman equivalent
```

## Usage

```bash
# add a new client (generates key, bootstraps a wbstream room, enables systemd unit)
sudo olcrtcctl add phone-pixel

# show the import URI / QR (paste into the Android client)
sudo olcrtcctl show phone-pixel

# all clients with their status
sudo olcrtcctl list

# tail the journal of one client
sudo olcrtcctl logs phone-pixel -f

# wbstream room expired? bootstrap a new one and restart the unit
sudo olcrtcctl regen-room phone-pixel

# revoke a client
sudo olcrtcctl remove phone-pixel
```

`add` boots a temporary olcrtc with `-id any`, scrapes the room id out of
its log, persists `(ROOM_ID, CLIENT_ID, KEY, CARRIER, TRANSPORT, DNS)` into
`/etc/olcrtc/<id>.env`, then starts `olcrtc@<id>.service` for real. The
systemd unit reads that env file and runs olcrtc via `DynamicUser` with
`ProtectSystem=strict` / `ProtectHome=yes` / `PrivateTmp=yes`.

## Defaults

Override at install / invocation time via env vars:

```
OLCRTC_BIN=/usr/local/bin/olcrtc       # binary path
ETC_DIR=/etc/olcrtc                    # per-client env files
DATA_DIR=/var/lib/olcrtc               # olcrtc -data
DNS=1.1.1.1:53                         # upstream DNS (passed to olcrtc -dns)
CARRIER_DEFAULT=wbstream               # wbstream | jazz | telemost
TRANSPORT_DEFAULT=datachannel          # datachannel | vp8channel
BOOTSTRAP_TIMEOUT=30                   # seconds to wait for room creation
```

The room URI emitted by `show` follows the format from
[`olcrtc/docs/uri.md`](../../olcrtc/docs/uri.md) and is consumable by the
`Add [olcRTC]` flow in this app fork.
