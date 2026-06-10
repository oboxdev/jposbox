package com.jposbox.server;

import com.jposbox.config.AppConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.handlers.DefaultPrinterActionHandler;
import com.jposbox.server.handlers.HandshakeHandler;
import com.jposbox.server.handlers.HelloHandler;
import com.jposbox.server.handlers.NoOpHandler;
import com.jposbox.server.handlers.OpenCashboxHandler;
import com.jposbox.server.handlers.PrintReceiptHandler;
import com.jposbox.server.handlers.PrintXmlReceiptHandler;
import com.jposbox.server.handlers.StatusJsonHandler;
import com.jposbox.tls.SelfSignedCert;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Hosts the Odoo hw_proxy-compatible HTTP/HTTPS API. */
public class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

    private final AppConfig config;
    private final PrinterManager printerManager;

    private HttpServer httpServer;
    private HttpsServer httpsServer;
    private ExecutorService executor;

    public ApiServer(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    public void start() throws IOException {
        executor = Executors.newCachedThreadPool();

        httpServer = HttpServer.create(new InetSocketAddress(config.httpPort), 0);
        registerRoutes(httpServer);
        httpServer.setExecutor(executor);
        httpServer.start();
        LOG.info("HTTP API listening on port " + config.httpPort);

        if (config.httpsEnabled) {
            try {
                httpsServer = HttpsServer.create(new InetSocketAddress(config.httpsPort), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(buildSslContext()));
                registerRoutes(httpsServer);
                httpsServer.setExecutor(executor);
                httpsServer.start();
                LOG.info("HTTPS API listening on port " + config.httpsPort);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to start HTTPS server, continuing with HTTP only", e);
            }
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        if (httpsServer != null) {
            httpsServer.stop(1);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** Restarts both servers, e.g. after the user changes ports/printers. */
    public synchronized void restart() throws IOException {
        stop();
        start();
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore keyStore = SelfSignedCert.loadOrCreate();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, SelfSignedCert.password());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    private void registerRoutes(HttpServer server) {
        server.createContext("/hw_proxy/hello", new HelloHandler()).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/handshake", new HandshakeHandler()).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/status_json", new StatusJsonHandler(config, printerManager)).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/print_receipt", new PrintReceiptHandler(config, printerManager)).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/print_xml_receipt", new PrintXmlReceiptHandler(config, printerManager)).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/open_cashbox", new OpenCashboxHandler(config, printerManager)).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/default_printer_action", new DefaultPrinterActionHandler(config, printerManager)).getFilters().add(new CorsFilter());

        // Endpoints Odoo POS calls but that don't need real action here.
        for (String path : List.of("/hw_proxy/scan_item_success", "/hw_proxy/scan_item_error_unrecognized")) {
            server.createContext(path, new NoOpHandler(true)).getFilters().add(new CorsFilter());
        }
        server.createContext("/hw_proxy/test_ownership", new NoOpHandler(true)).getFilters().add(new CorsFilter());
        server.createContext("/hw_proxy/take_control", new NoOpHandler(java.util.Map.of("status", "OWNER"))).getFilters().add(new CorsFilter());
    }
}
