# Changelog

All notable changes to jPosBox are documented here. Versions follow
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

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
