# Phone Router for `e25c1394`

This repository contains a small control layer for turning the Xiaomi Mi 9 Lite
(`e25c1394`, LineageOS Android 15, arm64) into a phone-only travel router.

The phone keeps using Android's own cellular tethering/AP stack. The scripts add
router-style controls on top:

- show hotspot clients from tethering, neighbor, and ARP state
- block/unblock clients by MAC or IP
- pause/resume all client Internet access
- DNS hijack to Clash Meta DNS
- transparent proxy via Clash Meta `tproxy-port` or `redir-port`

No system partition files are modified. All firewall rules live in dedicated
`PHONE_ROUTER_*` chains and can be removed with `bin/phone-router disable`.

## Current Device Findings

- Device: Xiaomi Mi 9 Lite / `pyxis`
- ROM: LineageOS Android 15
- ABI: `arm64-v8a`
- `adb root`: works
- Kernel supports: `TPROXY`, `REDIRECT`, MAC matching, `tc`
- Installed proxy app: `com.github.metacubex.clash.meta`
- OpenClaw is already running in proot and listening on localhost `18789`

## Quick Start

```bash
cd /Users/aurigalim/Documents/phone-build
bin/phone-router status
```

## Phone App

Build and install the on-phone control app:

```bash
bin/phone-router install-app
```

Start the root localhost API from ADB:

```bash
bin/phone-router start-api
```

Then open **Phone Router** on the phone.

The app also has a **Start Root** button. If the phone has Magisk, KernelSU,
APatch, or another `su` provider, the app can copy the bundled scripts to
`/data/local/tmp` and start the root localhost API itself. On the current
`e25c1394` build, `adb root` works but no app-visible `su` binary was found, so
the ADB startup path is still required unless a root manager is installed.

Prepare Clash Meta profiles so the core exposes router ports:

```bash
bin/phone-router prepare-clash-ports
```

Then restart or reload Clash Meta on the phone. The command backs up each config
before changing it and adds these top-level keys when missing:

```yaml
allow-lan: true
bind-address: '*'
redir-port: 7892
tproxy-port: 7893
```

After the Android hotspot is on and Clash Meta is running, enable transparent
proxying:

```bash
bin/phone-router enable-tproxy
```

If UDP/TProxy causes trouble, use TCP-only redirection instead:

```bash
bin/phone-router disable
bin/phone-router enable-redir
```

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

## Recovery

Remove all firewall and policy-routing state added by this project:

```bash
bin/phone-router disable
```

If Android chooses a different hotspot interface, override detection:

```bash
DOWN_IFACE=wlan0 bin/phone-router enable-tproxy
```

## Notes

`enable-tproxy` is the preferred router-like mode because it can catch TCP and
UDP from hotspot clients. It requires Clash Meta to actually listen on
`tproxy-port: 7893`.

`enable-redir` is simpler and catches TCP only. It requires `redir-port: 7892`.

The first version intentionally does not implement per-client bandwidth quotas.
This phone has `tc`, so shaping can be added later, but client-specific shaping
on Android tethering needs more care than block/unblock rules.
