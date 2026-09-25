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
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * All requests go through a single "/" context: the printer a call targets is
     * encoded in the path (see {@link PrinterRouter}), so the endpoint can't be a
     * fixed HttpServer context any more. {@link RouteDispatcher} pulls the endpoint
     * out of the path and delegates here.
     */
    private void registerRoutes(HttpServer server) {
        PrinterRouter router = new PrinterRouter(config);
        Map<String, HttpHandler> endpoints = new LinkedHashMap<>();

        endpoints.put("hello", new HelloHandler(router));
        endpoints.put("handshake", new HandshakeHandler());
        endpoints.put("status_json", new StatusJsonHandler(router, printerManager));
        endpoints.put("print_receipt", new PrintReceiptHandler(router, printerManager));
        endpoints.put("print_xml_receipt", new PrintXmlReceiptHandler(router, printerManager));
        endpoints.put("open_cashbox", new OpenCashboxHandler(router, printerManager));
        endpoints.put("default_printer_action", new DefaultPrinterActionHandler(router, printerManager));

        // Endpoints Odoo POS calls but that don't need real action here.
        endpoints.put("scan_item_success", new NoOpHandler(true));
        endpoints.put("scan_item_error_unrecognized", new NoOpHandler(true));
        endpoints.put("test_ownership", new NoOpHandler(true));
        endpoints.put("take_control", new NoOpHandler(Map.of("status", "OWNER")));

        server.createContext("/", new RouteDispatcher(endpoints)).getFilters().add(new CorsFilter());
    }
}
