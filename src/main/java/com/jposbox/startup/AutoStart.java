package com.jposbox.startup;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Registers/unregisters jPosBox to launch automatically on login (Windows/macOS/Linux). */
public class AutoStart {

    private static final Logger LOG = Logger.getLogger(AutoStart.class.getName());
    private static final String APP_NAME = "jPosBox";

    /** Enables or disables launch-on-login for the currently running packaged app. */
    public static void apply(boolean enabled) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Optional<String> exePath = ProcessHandle.current().info().command();
        if (exePath.isEmpty() || isJavaLauncher(exePath.get())) {
            return; // running via `java -jar` (dev/test), not a packaged jpackage app; nothing to register
        }
        try {
            if (os.contains("win")) {
                applyWindows(enabled, exePath.get());
            } else if (os.contains("mac")) {
                applyMac(enabled, exePath.get());
            } else {
                applyLinux(enabled, exePath.get());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to update launch-on-login setting", e);
        }
    }

    private static void applyWindows(boolean enabled, String exePath) throws IOException, InterruptedException {
        String[] cmd = enabled
                ? new String[]{"reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v", APP_NAME, "/t", "REG_SZ", "/d", exePath, "/f"}
                : new String[]{"reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v", APP_NAME, "/f"};
        run(cmd);
    }

    private static void applyMac(boolean enabled, String exePath) throws IOException, InterruptedException {
        Path plist = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "com.jposbox.app.plist");
        if (enabled) {
            Files.createDirectories(plist.getParent());
            try (Writer w = Files.newBufferedWriter(plist, StandardCharsets.UTF_8)) {
                w.write("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>Label</key>
                            <string>com.jposbox.app</string>
                            <key>ProgramArguments</key>
                            <array>
                                <string>%s</string>
                            </array>
                            <key>RunAtLoad</key>
                            <true/>
                        </dict>
                        </plist>
                        """.formatted(exePath));
            }
            run(new String[]{"launchctl", "load", "-w", plist.toString()});
        } else {
            run(new String[]{"launchctl", "unload", "-w", plist.toString()});
            Files.deleteIfExists(plist);
        }
    }

    private static void applyLinux(boolean enabled, String exePath) throws IOException {
        Path desktopFile = Path.of(System.getProperty("user.home"), ".config", "autostart", "jposbox.desktop");
        if (enabled) {
            Files.createDirectories(desktopFile.getParent());
            try (Writer w = Files.newBufferedWriter(desktopFile, StandardCharsets.UTF_8)) {
                w.write("""
                        [Desktop Entry]
                        Type=Application
                        Name=jPosBox
                        Exec=%s
                        X-GNOME-Autostart-enabled=true
                        """.formatted(exePath));
            }
        } else {
            Files.deleteIfExists(desktopFile);
        }
    }

    private static boolean isJavaLauncher(String exePath) {
        String name = Path.of(exePath).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }

    private static void run(String[] cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.waitFor();
    }
}
