package com.jposbox;

import com.jposbox.config.AppConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.ApiServer;
import com.jposbox.startup.AutoStart;
import com.jposbox.ui.TrayApp;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {

    public static void main(String[] args) throws Exception {
        setupLogging();

        AppConfig config = AppConfig.load();
        AutoStart.apply(config.runAtStartup);
        PrinterManager printerManager = new PrinterManager();
        ApiServer apiServer = new ApiServer(config, printerManager);
        apiServer.start();

        if (!GraphicsEnvironment.isHeadless()) {
            TrayApp trayApp = new TrayApp(config, printerManager, apiServer);
            trayApp.start();
        } else {
            Logger.getLogger("com.jposbox").info("Headless environment, running as background service.");
        }
    }

    private static void setupLogging() {
        try {
            Path logDir = AppConfig.homeDir().resolve("logs");
            java.nio.file.Files.createDirectories(logDir);
            FileHandler handler = new FileHandler(logDir.resolve("app.log").toString(), 1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(handler);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Failed to set up file logging: " + e.getMessage());
        }
    }
}
