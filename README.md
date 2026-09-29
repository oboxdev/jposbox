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
- Set the **Odoo route** for each printer (or leave it blank to derive it from
  the name) — this is how Odoo picks *which* printer a job goes to. See
  [Multiple printers](#multiple-printers).
- Mark one printer as **Default** — this is the one used when a request carries
  no route.
- Use **Test Print** / **Open Drawer** to verify the connection, and
  **Odoo URL** to copy the exact value to paste into Odoo's *Proxy IP* field.

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

### macOS code signing & notarization (optional)

Without signing, macOS users see "No se abrió" / "Apple no pudo verificar" and
must right-click > Open (or `xattr -cr jPosBox.app`) on first launch.

To produce a signed + notarized `.dmg`, set these repo secrets (Settings >
Secrets and variables > Actions). If `MACOS_CERTIFICATE` is unset, the
workflow falls back to building an unsigned dmg as before.

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE` | Base64 of your "Developer ID Application" `.p12` export (`base64 -i cert.p12`) |
| `MACOS_CERTIFICATE_PWD` | Password used when exporting the `.p12` |
| `MACOS_KEYCHAIN_PWD` | Any random password, used for the temporary CI keychain |
| `MAC_SIGNING_IDENTITY` | Certificate name, e.g. `Developer ID Application: Your Name (TEAMID)` |
| `APPLE_ID` | Apple ID email used for notarization |
| `APPLE_TEAM_ID` | Apple Developer Team ID |
| `APPLE_APP_SPECIFIC_PASSWORD` | App-specific password for `APPLE_ID` (generate at appleid.apple.com) |

Requires an active Apple Developer Program membership ($99/year).

## Connect from Odoo POS

1. In Odoo: **Point of Sale > Configuration > Settings**, enable
   "IoT Box" / proxy printing for the POS, and set the **Proxy IP/host** to
   `https://192.168.1.50:8443` (use the **Odoo URL** button in the Printers
   tab to get this exact value for a given printer, already copied to the
   clipboard). Prefer this over a bare `192.168.1.50:8008` — see
   [Troubleshooting](#troubleshooting) below for why.
2. Open `https://<host>:8443/hw_proxy/hello` once in the browser used by the
   POS and accept the self-signed certificate warning (the cert is generated
   on first run, stored in `~/.jposbox/keystore.p12`, and covers every network
   address this machine currently has — not just `localhost`).
3. Print a receipt from the POS — it's converted from Odoo's receipt HTML to
   ESC/POS and sent to the default printer.

## Multiple printers

One jPosBox instance can drive several printers (cashier, kitchen, bar) on a
single port. Odoo picks the printer through the URL: it builds its request as
`<proxy_ip>` + `/hw_proxy/<endpoint>`, so a trailing path segment on the proxy
IP becomes a printer selector.

1. Give each printer a distinct **Odoo route** in the Printers tab (e.g.
   `kitchen`). Blank derives it from the name — "Cocina Caliente" becomes
   `cocina-caliente`. Matching ignores case, accents and separators.
2. In Odoo, **Point of Sale > Configuration > Printers**, create one
   `pos.printer` per device and set its **Proxy IP** to
   `<host>:<port>/<route>`, then assign its product categories.

| Odoo Proxy IP | Request that arrives | Printer used |
|---|---|---|
| `192.168.1.50:8008` | `/hw_proxy/print_receipt` | the **Default** printer |
| `192.168.1.50:8008/kitchen` | `/kitchen/hw_proxy/print_receipt` | `kitchen` |
| `192.168.1.50:8008/bar` | `/bar/hw_proxy/print_receipt` | `bar` |

The **Odoo URL** button in the Printers tab prints the exact string to paste
and copies it to the clipboard.

Two other forms select a printer as well, for tooling and manual tests:

```bash
curl http://192.168.1.50:8008/hw_proxy/kitchen/status_json     # route as infix
curl 'http://192.168.1.50:8008/hw_proxy/status_json?printer=kitchen'
```

A route that no printer answers to is **rejected**, not silently redirected to
the default printer — a typo would otherwise send kitchen orders to the
cashier. `hello` replies `404`, `status_json` reports
`{"status":"disconnected"}`, and print calls return a JSON-RPC error naming the
configured routes.

## Endpoints implemented

Every endpoint below also exists as `/<route>/hw_proxy/<endpoint>` and
`/hw_proxy/<route>/<endpoint>`; without a route it acts on the default printer.

- `GET  /hw_proxy/hello` — health check
- `POST /hw_proxy/handshake`
- `GET  /hw_proxy/status_json` — configured printers + reachability (only the
  routed printer when the path names one)
- `POST /hw_proxy/print_receipt` — `{ "receipt": "<html>..." }`
- `POST /hw_proxy/print_xml_receipt` — `{ "receipt": "<xml>..." }`, rendered as
  native ESC/POS via the legacy jIotBox tag set (text, alignment, bold, text
  size, tables, native QR/barcode, images, mid-receipt cuts) — see
  [jIotBox XML receipts](#jiotbox-xml-receipts)
- `POST /hw_proxy/open_cashbox`
- `POST /hw_proxy/default_printer_action` — Odoo 17-19: `{"data":{"action":"print_receipt","receipt":"<base64 ...>"}}`.
  Normally the payload is a rasterized image (JPEG/PNG), printed as a bitmap
  (scaled to `printerWidthPx`); if it doesn't decode as an image, it's sniffed
  as jIotBox XML and rendered the same way as `print_xml_receipt`.
  `action: "open_cashbox"`/`"cashbox"` pulses the drawer. Despite the name, it
  prints on the routed printer, not only on the default one
- `POST /hw_proxy/scan_item_success` / `scan_item_error_unrecognized` — no-ops
- `POST /hw_proxy/test_ownership` / `take_control` — no-ops

## jIotBox XML receipts

`print_xml_receipt`, and `default_printer_action` when its base64 payload
isn't a decodable image, render a flat XML tag set ported from the original
jIotBox project's Node.js receipt renderer, straight to ESC/POS (native QR
codes and barcodes, not rasterized). Alignment, bold and text size are
**global state that persists across tags** until changed again — exactly like
real printer commands, not scoped/inherited like CSS.

| Tag / attribute | Effect |
|---|---|
| `<receipt>` | Root element, no effect on its own |
| `<div>`, `<line>` | Grouping only (`<line>` also feeds one line) |
| `<br/>` | Feed one line |
| `<hr/>` | A dashed line the full width of the printer |
| `<left/>` / `<right/>`, or `align="left"\|"center"\|"right"` (any tag) | Sets alignment, persists until changed |
| `font="a"` / `font="b"` (any tag) | Bold off / on, persists until changed |
| `<textnormal/>` / `<textdoublewidth/>` / `<textdoubleheight/>` | Text size, persists until changed |
| `<table><tr><td width="0.xx" align="..." font="...">` | One physical line per `<tr>`, columns laid out proportionally by `width` (defaults to an even split); each `<td>` keeps its own alignment and bold |
| `<qrcode>text</qrcode>` | Native ESC/POS QR code |
| `<barcode>text</barcode>` | Native ESC/POS CODE128 barcode |
| `<img src="data:image/png;base64,...">` | Rasterized inline image, scaled to `printerWidthPx`. No `src` (or an undecodable one) is skipped, not an error |
| `<cut/>` | Cuts the paper immediately, mid-receipt |

```xml
<receipt>
  <div align="center" font="b"><textdoublewidth/>MY STORE<textnormal/></div>
  <hr/>
  <table><tr><td width="0.6">2x Coffee</td><td width="0.4" align="right">8.00</td></tr></table>
  <div align="right" font="b">TOTAL 8.00</div>
  <div align="center"><qrcode>https://example.com/receipt/42</qrcode></div>
  <cut/>
</receipt>
```

This tag set is unrelated to `print_receipt`'s Odoo `pos-receipt` HTML
renderer (div/table markup with `pos-receipt-*` classes) — the two are
separate code paths for separate receipt formats.

## Limitations (v1)

- `print_receipt`'s Odoo `pos-receipt` HTML rendering covers text, alignment,
  bold and simple tables. Logos, QR codes and barcodes embedded as `<img>` in
  that HTML are **not** rendered (the jIotBox XML tag set above does support
  them, natively).
- Full Odoo 17/18 IoT "hw_drivers" framework (websocket device manager,
  mDNS, Odoo-signed certs) is **not** implemented — only the classic
  `hw_proxy` HTTP contract, which Odoo POS still uses for direct printing.

## Troubleshooting

### "Blocked mixed content" / "Mixed Content ... has been blocked" — works on localhost, not by IP

The POS page is served over HTTPS and its **Proxy IP** points at a plain
`http://` address (or a bare `192.168.1.50:8008` with no scheme, which Odoo
treats as `http://`). Browsers block that as mixed content — **except** for
`localhost`/`127.0.0.1`, which they always treat as safe regardless of scheme.
That's exactly why it works from the same machine and breaks for everyone
else: it was never actually about the printer or the network, only about
which hostname the browser was willing to trust.

Fix: point the Proxy IP at `https://<host>:8443` instead (jPosBox's HTTPS
port, enabled by default). Use the **Odoo URL** button in the Printers tab to
get the right value copied to the clipboard automatically. The very first
time, also open `https://<host>:8443/hw_proxy/hello` in the browser the POS
runs in and accept the self-signed certificate warning — skipping this step
trades the mixed-content error for a silent connection failure instead
(the browser refuses the untrusted certificate, invisibly to the POS UI).

If Chrome's per-site "accept the warning" flow isn't an option (e.g. a kiosk
browser with no way to click through an interstitial), two alternatives that
don't require switching to HTTPS at all:
- Site settings → click the address bar's site icon → **Site settings** →
  **Insecure content** → **Allow**, for the POS's own URL.
- `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, adding the exact
  origin (e.g. `http://192.168.1.50:8008`) — or the equivalent
  `--unsafely-treat-insecure-origin-as-secure=http://192.168.1.50:8008`
  command-line flag for a kiosk launch script.

Both narrow the exception to that one address on that one browser/profile —
reasonable on a trusted store LAN, but something to repeat on every POS
terminal, unlike fixing it once via HTTPS above.

### Switched to HTTPS, but it still doesn't work (certificate error, or still silently fails)

Chrome and other modern browsers validate a certificate's **Subject
Alternative Name (SAN)** only — they ignore the CN entirely. jPosBox's
self-signed certificate must list the exact address typed in the Proxy IP as
a SAN entry, or the browser rejects it even after "accepting" it once.

This is generated automatically and should just work, but on first HTTPS
setup — or after this machine's IP address changes (a new DHCP lease, moving
networks, etc.) — the certificate can need regenerating. jPosBox detects a
stale certificate (current network addresses no longer covered by it) and
regenerates automatically on the next restart; to force it immediately,
quit jPosBox and delete `~/.jposbox/keystore.p12`, then start it again — a
fresh certificate covering every current network address is created on
startup. Then repeat the "open `/hw_proxy/hello` once and accept the warning"
step, since a new certificate needs accepting again.

### Printouts come out garbled after a few in a row, but the first one or two look fine

Almost always a timing issue with the printer's cutter, not an encoding bug —
a real encoding problem would garble the very first printout too, not only
after several. Closing the connection right after sending the cut command
gives a budget thermal printer's cutter motor and receive buffer zero time to
finish before the *next* job's connection can arrive; if prints come in fast
(a busy POS firing off a kitchen ticket and a receipt back to back, or several
orders in a row), the printer's ESC/POS parser can desync mid-cut and start
interpreting the next job's bytes as garbage.

jPosBox already waits after cutting/opening the drawer before closing the
connection (**Delay after cut (ms)** in the printer's settings, default
`250`), and serializes jobs sent to the same printer so two can never reach it
at the same time. If it's still happening on your printer, raise that delay
— `500`–`800` is reasonable for a slower cutter — in the Printers tab, edit
the printer, and **Test Print** a few times in quick succession to confirm.

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
  auto-migrated and renamed to `config.json.bak`). The `printers.slug` column is
  added automatically on first run of 1.1.0+
- TLS keystore: `~/.jposbox/keystore.p12` (self-signed, auto-regenerated
  whenever this machine's network addresses no longer match it)
- Logs: `~/.jposbox/logs/app.log`
