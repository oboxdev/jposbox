package com.jposbox.config;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** App configuration, persisted in a SQLite database at ~/.jposbox/config.db. */
public class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());
    private static final Gson GSON = new Gson();

    public int httpPort = 8008;
    public int httpsPort = 8443;
    public boolean httpsEnabled = true;
    public String updateCheckUrl = "";
    public List<PrinterConfig> printers = new ArrayList<>();

    public static Path homeDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".jposbox");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    public static Path dbFile() {
        return homeDir().resolve("config.db");
    }

    private static String jdbcUrl() {
        return "jdbc:sqlite:" + dbFile();
    }

    public static AppConfig load() {
        AppConfig cfg = new AppConfig();
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            createSchema(conn);
            migrateLegacyJsonIfNeeded(conn);
            loadSettings(conn, cfg);
            loadPrinters(conn, cfg);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Failed to load config DB, using defaults", e);
        }
        return cfg;
    }

    public void save() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            createSchema(conn);
            conn.setAutoCommit(false);
            saveSettings(conn);
            savePrinters(conn);
            conn.commit();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Failed to save config DB", e);
        }
    }

    public Optional<PrinterConfig> getDefaultPrinter() {
        return printers.stream().filter(p -> p.isDefault).findFirst()
                .or(() -> printers.stream().findFirst());
    }

    public Optional<PrinterConfig> getPrinter(String name) {
        return printers.stream().filter(p -> p.name.equals(name)).findFirst();
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS printers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        is_default INTEGER NOT NULL DEFAULT 0,
                        host TEXT,
                        port INTEGER,
                        system_printer_name TEXT,
                        cut_after_print INTEGER NOT NULL DEFAULT 1,
                        open_drawer_after_print INTEGER NOT NULL DEFAULT 0,
                        char_width INTEGER NOT NULL DEFAULT 42,
                        printer_width_px INTEGER NOT NULL DEFAULT 576
                    )
                    """);
        }
    }

    private static void loadSettings(Connection conn, AppConfig cfg) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM settings")) {
            while (rs.next()) {
                String value = rs.getString("value");
                switch (rs.getString("key")) {
                    case "httpPort" -> cfg.httpPort = Integer.parseInt(value);
                    case "httpsPort" -> cfg.httpsPort = Integer.parseInt(value);
                    case "httpsEnabled" -> cfg.httpsEnabled = Boolean.parseBoolean(value);
                    case "updateCheckUrl" -> cfg.updateCheckUrl = value;
                    default -> { /* ignore unknown keys */ }
                }
            }
        }
    }

    private static void loadPrinters(Connection conn, AppConfig cfg) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM printers ORDER BY id")) {
            while (rs.next()) {
                PrinterConfig p = new PrinterConfig();
                p.id = rs.getLong("id");
                p.name = rs.getString("name");
                p.type = PrinterConfig.Type.valueOf(rs.getString("type"));
                p.isDefault = rs.getInt("is_default") != 0;
                p.host = rs.getString("host");
                p.port = rs.getInt("port");
                p.systemPrinterName = rs.getString("system_printer_name");
                p.cutAfterPrint = rs.getInt("cut_after_print") != 0;
                p.openDrawerAfterPrint = rs.getInt("open_drawer_after_print") != 0;
                p.charWidth = rs.getInt("char_width");
                p.printerWidthPx = rs.getInt("printer_width_px");
                cfg.printers.add(p);
            }
        }
    }

    private void saveSettings(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO settings(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, "httpPort");
            ps.setString(2, String.valueOf(httpPort));
            ps.addBatch();
            ps.setString(1, "httpsPort");
            ps.setString(2, String.valueOf(httpsPort));
            ps.addBatch();
            ps.setString(1, "httpsEnabled");
            ps.setString(2, String.valueOf(httpsEnabled));
            ps.addBatch();
            ps.setString(1, "updateCheckUrl");
            ps.setString(2, updateCheckUrl == null ? "" : updateCheckUrl);
            ps.addBatch();
            ps.executeBatch();
        }
    }

    private void savePrinters(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM printers");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO printers(name, type, is_default, host, port, system_printer_name, "
                        + "cut_after_print, open_drawer_after_print, char_width, printer_width_px) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (PrinterConfig p : printers) {
                ps.setString(1, p.name);
                ps.setString(2, p.type.name());
                ps.setInt(3, p.isDefault ? 1 : 0);
                ps.setString(4, p.host);
                ps.setInt(5, p.port);
                ps.setString(6, p.systemPrinterName);
                ps.setInt(7, p.cutAfterPrint ? 1 : 0);
                ps.setInt(8, p.openDrawerAfterPrint ? 1 : 0);
                ps.setInt(9, p.charWidth);
                ps.setInt(10, p.printerWidthPx);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM printers ORDER BY id")) {
            int i = 0;
            while (rs.next() && i < printers.size()) {
                printers.get(i).id = rs.getLong("id");
                i++;
            }
        }
    }

    /** One-time import from the old ~/.jposbox/config.json (pre-SQLite versions). */
    private static void migrateLegacyJsonIfNeeded(Connection conn) throws SQLException {
        Path legacy = homeDir().resolve("config.json");
        if (!Files.exists(legacy)) {
            return;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM settings")) {
            rs.next();
            if (rs.getInt(1) > 0) {
                return; // DB already populated, don't overwrite
            }
        }
        try (Reader r = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            AppConfig old = GSON.fromJson(r, AppConfig.class);
            if (old == null) {
                return;
            }
            if (old.printers == null) {
                old.printers = new ArrayList<>();
            }
            old.save();
            LOG.info("Migrated legacy config.json into SQLite config.db");
            Files.move(legacy, legacy.resolveSibling("config.json.bak"));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to migrate legacy config.json", e);
        }
    }
}
