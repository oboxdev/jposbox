# Changelog

All notable changes to jPosBox are documented here. Versions follow
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

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
