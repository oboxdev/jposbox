package com.jposbox.ui;

import com.jposbox.config.AppConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.ApiServer;
import com.jposbox.update.UpdateChecker;
import com.jposbox.update.UpdateInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Optional;

/** System tray icon: status + access to the configuration window. */
public class TrayApp {

    private final AppConfig config;
    private final PrinterManager printerManager;
    private final ApiServer apiServer;

    private ConfigWindow configWindow;
    private TrayIcon trayIcon;
    private boolean serverRunning = true;

    public TrayApp(AppConfig config, PrinterManager printerManager, ApiServer apiServer) {
        this.config = config;
        this.printerManager = printerManager;
        this.apiServer = apiServer;
    }

    public void start() {
        if (!SystemTray.isSupported()) {
            SwingUtilities.invokeLater(this::openConfigWindow);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu menu = new PopupMenu();

            MenuItem openItem = new MenuItem("Open jPosBox");
            openItem.addActionListener(e -> openConfigWindow());

            CheckboxMenuItem toggleItem = new CheckboxMenuItem("Server running", true);
            toggleItem.addItemListener(e -> {
                serverRunning = toggleItem.getState();
                try {
                    if (serverRunning) {
                        apiServer.start();
                    } else {
                        apiServer.stop();
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, "Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    toggleItem.setState(!toggleItem.getState());
                }
            });

            MenuItem quitItem = new MenuItem("Quit");
            quitItem.addActionListener(e -> {
                apiServer.stop();
                tray.remove(trayIcon);
                System.exit(0);
            });

            menu.add(openItem);
            menu.addSeparator();
            menu.add(toggleItem);
            menu.addSeparator();
            menu.add(quitItem);

            trayIcon = new TrayIcon(buildIconImage(), "jPosBox", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> openConfigWindow());

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                openConfigWindow();
            }

            checkForUpdatesInBackground();
        });
    }

    /** Checks the configured update URL on startup and shows a tray notification if a newer version exists. */
    private void checkForUpdatesInBackground() {
        if (config.updateCheckUrl == null || config.updateCheckUrl.isBlank()) {
            return;
        }
        new Thread(() -> {
            Optional<UpdateInfo> update = UpdateChecker.checkForUpdate(config.updateCheckUrl, UpdateChecker.getCurrentVersion());
            update.ifPresent(info -> {
                if (trayIcon != null) {
                    trayIcon.displayMessage("jPosBox update available",
                            "Version " + info.version + " is available (current: " + UpdateChecker.getCurrentVersion() + ")",
                            TrayIcon.MessageType.INFO);
                }
            });
        }, "update-check").start();
    }

    private void openConfigWindow() {
        if (configWindow == null) {
            configWindow = new ConfigWindow(config, printerManager, apiServer);
        }
        configWindow.setVisible(true);
        configWindow.toFront();
    }

    /** Loads the bundled app icon for the tray, falling back to a drawn placeholder. */
    private Image buildIconImage() {
        java.net.URL resource = getClass().getResource("/icons/icon.png");
        if (resource != null) {
            return Toolkit.getDefaultToolkit().getImage(resource);
        }

        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(0x2E, 0x7D, 0x32));
        g.fillRoundRect(2, 10, 28, 14, 6, 6);

        g.setColor(Color.WHITE);
        g.fillRect(8, 4, 16, 10);
        g.fillRect(8, 20, 16, 8);

        g.setColor(new Color(0x1B, 0x5E, 0x20));
        g.fillRect(10, 14, 12, 2);

        g.dispose();
        return image;
    }
}
