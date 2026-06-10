# jPosBox

Cross-platform (macOS, Windows, Linux) Java app that exposes ESC/POS printing
over the network, acting as a local replacement for Odoo's IoT Box "printer
proxy" — useful when you don't have a physical IoT Box and just want to print
receipts/kitchen tickets from Odoo POS to a thermal printer (network or
USB/system printer).

It is a clean-room implementation (not based on jIotBox's binary) that speaks
the same `hw_proxy` HTTP API Odoo POS already calls when "Use Proxy" is
enabled with an IoT Box IP.

## Build

Requires JDK 17+ and Gradle (no install needed if `gradle` is on PATH).

```bash
./gradlew shadowJar          # or: gradle shadowJar
java -jar build/libs/jPosBox-0.1.0-all.jar
```

A tray icon appears (green printer icon). Click it to open the configuration
window.

## Configure

In the **Printers** tab:
- **Add** a printer:
  - `NETWORK`: ESC/POS thermal printer reachable on the LAN, usually port `9100`.
  - `SYSTEM`: a printer already installed/registered with the OS (USB printers
    typically show up here once their driver is installed).
- Mark one printer as **Default** — this is the one used for `print_receipt`
  and `open_cashbox`.
- Use **Test Print** / **Open Drawer** to verify the connection.

In the **Server** tab, set the HTTP/HTTPS ports (defaults `8008` / `8443`) and
restart the server after changes.

In the **About** tab you can see the current version and optionally set an
**update check URL** pointing to a JSON file (`{"version":"1.1.0","url":"...","notes":"..."}`).
Click **Check for updates** to check manually, or leave it configured and the
app will check once on startup and show a tray notification if a newer
version is available. This never auto-installs — it only notifies.

## Releases & updates

Tagging a commit `vX.Y.Z` and pushing it triggers
`.github/workflows/release.yml`, which builds the installer for macOS,
Windows and Linux (via `jpackage`) and attaches them to a GitHub Release.

To enable update notifications, set the **update check URL** (About tab) to:

```
https://raw.githubusercontent.com/oboxdev/jposbox/main/update.json
```

After each release, bump `version` in `update.json` (and `build.gradle.kts`,
and add a `CHANGELOG.md` entry) and push to `main` — clients will then see the
new version on their next check.

## Connect from Odoo POS

1. In Odoo: **Point of Sale > Configuration > Settings**, enable
   "IoT Box" / proxy printing for the POS, and set the **Proxy IP/host** to the
   machine running jPosBox (e.g. `192.168.1.50:8008`, or
   `https://192.168.1.50:8443` for HTTPS).
2. If using HTTPS, open `https://<host>:8443/hw_proxy/hello` once in the
   browser used by the POS and accept the self-signed certificate warning
   (the cert is generated on first run, stored in `~/.jposbox/keystore.p12`).
3. Print a receipt from the POS — it's converted from Odoo's receipt HTML to
   ESC/POS and sent to the default printer.

## Endpoints implemented

- `GET  /hw_proxy/hello` — health check
- `POST /hw_proxy/handshake`
- `GET  /hw_proxy/status_json` — configured printers + reachability
- `POST /hw_proxy/print_receipt` — `{ "receipt": "<html>..." }`
- `POST /hw_proxy/print_xml_receipt` — compat stub, prints text content only
- `POST /hw_proxy/open_cashbox`
- `POST /hw_proxy/default_printer_action` — Odoo 19: `{"data":{"action":"print_receipt","receipt":"<base64 JPEG/PNG>"}}`,
  printed as a raster bitmap (scaled to `printerWidthPx`); `action: "open_cashbox"`/`"cashbox"` pulses the drawer
- `POST /hw_proxy/scan_item_success` / `scan_item_error_unrecognized` — no-ops
- `POST /hw_proxy/test_ownership` / `take_control` — no-ops

## Limitations (v1)

- Receipt rendering covers text, alignment, bold and simple tables. Logos,
  QR codes and barcodes embedded as `<img>` are **not** rendered yet.
- Full Odoo 17/18 IoT "hw_drivers" framework (websocket device manager,
  mDNS, Odoo-signed certs) is **not** implemented — only the classic
  `hw_proxy` HTTP contract, which Odoo POS still uses for direct printing.

## Packaging

```bash
gradle jpackage
```

Produces a native installer in `build/jpackage/`, bundling its own JRE (no
Java install needed on the target machine). `jpackage` builds for the OS it
runs on — build on each target OS separately:

- **macOS**: `jPosBox-1.0.0.dmg` (drag-to-Applications installer).
  Unsigned — first launch needs right-click > Open to bypass Gatekeeper.
- **Windows**: `jPosBox-1.0.0.msi`. Requires
  [WiX Toolset v3](https://wixtoolset.org/) installed (jpackage's MSI backend
  depends on it). Adds Start Menu shortcut + desktop shortcut
  (`--win-shortcut --win-menu`).
- **Linux**: `.deb` package (run on a Debian/Ubuntu-based system).

## Config & logs

- Config: `~/.jposbox/config.db` (SQLite; legacy `config.json` is
  auto-migrated and renamed to `config.json.bak`)
- TLS keystore: `~/.jposbox/keystore.p12`
- Logs: `~/.jposbox/logs/app.log`
