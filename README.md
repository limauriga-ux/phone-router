# Phone Router

Android phone-only travel router control app and root helper scripts.

The app uses Android's hotspot/tethering stack for AP access, then adds a
root-controlled routing layer on top:

- Start/stop hotspot and manual tethering helpers
- Show AP clients and block/unblock by MAC or IP
- Pause/resume all AP client Internet access
- Run sing-box as the proxy core
- Switch sing-box nodes from the app
- Switch between global proxy mode and China-direct rule mode
- View sing-box logs and client state from dedicated pages
- Import a complete sing-box JSON config from the phone

No system partition files are modified. Firewall rules are installed in
dedicated `PHONE_ROUTER_*` chains and can be removed with:

```bash
bin/phone-router disable
```

## Requirements

- Android phone with root access through Magisk, KernelSU, APatch, or `adb root`
- Android SDK platform tools available on the host
- A sing-box Android arm64 binary installed on the phone at:

```text
/data/local/phone-router/sing-box
```

The project currently uses sing-box directly. Clash Meta is not required for
the app's normal proxy modes.

## Quick Start

Set your device serial when more than one Android device is connected:

```bash
export ANDROID_SERIAL=<your-device-serial>
```

Install the helper scripts:

```bash
bin/phone-router install
```

Start the root localhost API used by the app:

```bash
bin/phone-router start-api
```

Build and install the Android control app:

```bash
bin/phone-router install-app
```

Then open **Phone Router** on the phone.

The app also has a **Start Root** button. If the phone has an app-visible `su`
provider, the app can copy the bundled scripts to `/data/local/tmp` and start
the root localhost API itself.

## Proxy Modes

Global mode:

```bash
bin/phone-router proxy-global
```

Rule mode:

```bash
bin/phone-router proxy-rule
```

Rule mode routes China domain/IP traffic directly and sends other traffic
through the selected sing-box node. The China rule sets bundled in the app come
from MetaCubeX `meta-rules-dat` sing-box rule-set exports.

Stop proxying:

```bash
bin/phone-router proxy-stop
```

## Node Config

List nodes:

```bash
bin/phone-router singbox-nodes
```

Switch node:

```bash
bin/phone-router singbox-set-node <node-tag>
```

Import a complete sing-box config already copied onto the phone:

```bash
bin/phone-router singbox-import-config /sdcard/Download/sing-box.json
```

The imported config is checked with `sing-box check` before it replaces the
active global config. Existing configs are backed up under
`/data/local/phone-router`.

## Client Management

```bash
bin/phone-router clients
bin/phone-router block-mac aa:bb:cc:dd:ee:ff
bin/phone-router unblock-mac aa:bb:cc:dd:ee:ff
bin/phone-router block-ip 192.168.43.123
bin/phone-router unblock-ip 192.168.43.123
bin/phone-router pause-all
bin/phone-router resume-all
bin/phone-router blocks
```

## Hotspot And Tethering

```bash
bin/phone-router start-ap PhoneRouter phonebuild888 2
bin/phone-router stop-ap
bin/phone-router enable-tether
bin/phone-router disable-tether
bin/phone-router tether-status
```

If Android chooses a different hotspot interface, override detection:

```bash
DOWN_IFACE=wlan0 bin/phone-router proxy-rule
```

## Legacy Clash Helper

The repository still contains `prepare-clash-ports` for old Clash Meta testing
profiles, but this is not part of the current app flow and is not required when
using the built-in sing-box integration.
