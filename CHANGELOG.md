# Changelog

All notable changes to jPosBox are documented here. Versions follow
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## [1.2.1] - 2026-09-29

### Fixed
- Printouts could come out garbled after a few consecutive jobs on the same
  printer. Two contributing causes, both fixed:
  - The connection was closed immediately after sending the cut/drawer
    command, giving a budget printer's cutter motor and receive buffer no
    time to finish before the next job's connection could arrive, desyncing
    its ESC/POS parser. A configurable **Delay after cut (ms)** (default
    `250`, per printer) now runs before the connection closes.
  - Print/cashbox requests to the same physical printer (matched by
    host:port or OS printer name, not by config row) are now serialized.
    The HTTP server's unbounded thread pool could otherwise open concurrent
    connections to one printer from two overlapping requests (e.g. a kitchen
    ticket and a receipt fired back to back) — most budget ESC/POS printers
    only expect one active connection and can interleave or drop bytes from
    two at once, indistinguishable from a garbled encoding bug at the
    printout.

## [1.2.0] - 2026-09-29

### Added
- Native ESC/POS rendering of the legacy jIotBox XML receipt tag set (text,
  alignment, bold, text size, tables, native QR codes and CODE128 barcodes,
  inline images, mid-receipt cuts), ported from that project's Node.js
  `parseHtmltoPrint()`. Alignment/bold/text-size are global state that
  persists across tags, matching real ESC/POS command behavior.
  - `print_xml_receipt` now renders this tag set instead of stripping to
    plain text.
  - `default_printer_action` sniffs its base64 payload: a valid image still
    prints as a raster bitmap as before; text that doesn't decode as an image
    is rendered as jIotBox XML instead of failing.
- `PrinterManager.writeRasterImage()`: the raster-image encoding used by
  `printImage()` is now reusable mid-job, so `<img>` tags can be embedded
  inline within an otherwise text-based receipt.

### Fixed
- The **Odoo URL** button (Printers tab) suggested a bare `host:port` Proxy
  IP, which Odoo treats as plain `http://`. Browsers block that as "mixed
  content" the moment the POS itself is served over HTTPS (Odoo.sh, or any
  TLS-terminated production Odoo) — everywhere except `localhost`, which is
  why it only ever showed up for real (non-local) users. It now suggests
  `https://<host>:<httpsPort>` when HTTPS is enabled (the default), with a
  reminder to accept the self-signed certificate once.
- The self-signed HTTPS certificate's Subject Alternative Name only ever
  covered `InetAddress.getLocalHost()`, which on a machine with several
  network interfaces (Wi-Fi + Ethernet + a VPN adapter, common on laptops)
  frequently resolves to the wrong one, or to a loopback address — with no
  relation to the LAN IP other devices actually use to reach this machine.
  Since Chrome and other modern browsers validate the SAN only (ignoring the
  CN), this made HTTPS silently unusable from anything but the address that
  happened to be picked, `localhost` included. The certificate now lists
  every non-loopback IPv4 address across every network interface, and is
  regenerated automatically if this machine's addresses change after it was
  first issued (e.g. a new DHCP lease) instead of staying stale forever.

## [1.1.0] - 2026-09-24

### Added
- Route multiple printers from a single jPosBox instance. Every `hw_proxy`
  endpoint now also answers at `/<route>/hw_proxy/<endpoint>` (the form Odoo
  produces when a `pos.printer`'s *Proxy IP* carries a trailing path, e.g.
  `192.168.1.50:8008/kitchen`) and at `/hw_proxy/<route>/<endpoint>`, printing
  on the printer that owns that route instead of always on the default one.
  A `?printer=<route>` query parameter does the same for manual testing.
- Per-printer **Odoo route** key (`printers.slug`), editable in the Printers
  tab and shown as a column. Blank derives the route from the printer name;
  matching is slug-normalised, so "Cocina Caliente", `cocina-caliente` and
  `COCINA_CALIENTE` all resolve to the same printer. Duplicate routes raise a
  warning, since only the first such printer would be reachable.
- **Odoo URL** button in the Printers tab: shows and copies the exact value to
  paste into Odoo's *Proxy IP* field for the selected printer.
- `status_json` reports the `route` of each printer, and reports only the routed
  printer when the request path names one, so each `pos.printer` in Odoo sees
  its own device's reachability rather than every printer on the box.

### Changed
- Requests to a route no printer answers to now fail loudly instead of falling
  back to the default printer — a mistyped route would otherwise print kitchen
  orders on the cashier's printer. `hello` replies `404`, `status_json` reports
  `disconnected`, and print calls return a JSON-RPC error listing the
  configured routes.
- The HTTP server registers a single root context and resolves the endpoint from
  the path, instead of one fixed context per endpoint, since the route key can
  precede or follow `hw_proxy`. Unknown paths return `404`.

## [1.0.3] - 2026-06-11

### Added
- "Download & Install" button in the About tab: downloads the installer
  matching the current OS from GitHub Releases (`~/.jposbox/updates/`) and
  opens it with the OS's native handler (Finder/dmg, msiexec, deb). The user
  still has to click through the installer — nothing is installed silently.

## [1.0.2] - 2026-06-11

### Fixed
- `status_json` no longer creates an OS print-queue job for SYSTEM/USB
  printers on every poll. Connectivity for those is now checked via the
  `PrintService` status (`PrinterIsAcceptingJobs`) instead of opening a
  `PrinterOutputStream`, which was saturating the queue under Odoo's
  periodic status polling.

## [1.0.1] - 2026-06-11

### Fixed
- "Failed to launch JVM" on the packaged Windows app — `jpackage` now bundles
  all JDK modules (`--add-modules ALL-MODULE-PATH`) instead of relying on
  jlink's automatic detection, which missed reflection-based usages
  (sqlite-jdbc, BouncyCastle, `java.net.http`).
- Update check URL (About tab) now defaults to the project's `update.json` on
  GitHub instead of being blank.

### Added
- Launch jPosBox automatically at login (Windows registry `Run` key, macOS
  LaunchAgent, Linux `~/.config/autostart`), toggleable from the Server tab.
- Optional macOS code signing & notarization in the release workflow.

## [1.0.0] - 2026-06-09

### Added
- Initial release: cross-platform Java app (Java 17) acting as a local
  Odoo IoT Box printer-proxy replacement.
- ESC/POS printing over network (TCP/9100) and OS-registered (USB/system)
  printers via `escpos-coffee` and `javax.print`.
- HTML receipt rendering (`pos-receipt`) to ESC/POS via Jsoup.
- `hw_proxy` HTTP API: `hello`, `handshake`, `status_json`, `print_receipt`,
  `print_xml_receipt`, `open_cashbox`, `scan_item_*`, `test_ownership`,
  `take_control`.
- Odoo 19 `default_printer_action` endpoint with image-based (raster) receipt
  printing.
- Optional HTTPS with auto-generated self-signed certificate (BouncyCastle).
- SQLite-backed configuration (`~/.jposbox/config.db`), with automatic
  migration from the legacy `config.json`.
- System tray icon + Swing configuration window (Printers, Server, Logs,
  About tabs).
- Native installers via `jpackage` (macOS `.dmg`, Windows `.msi`, Linux `.deb`).
- Version display and manual/automatic update checking against a configurable
  JSON manifest URL (no auto-install — notification only).
