package com.jposbox.server;

import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives a live ApiServer to check the path -> printer routing end to end. */
class RoutingIntegrationTest {

    private ApiServer server;
    private int port;
    private FakePrinter cashierDevice;
    private FakePrinter kitchenDevice;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        AppConfig config = new AppConfig();
        config.httpsEnabled = false;
        config.httpPort = freePort();
        this.port = config.httpPort;

        cashierDevice = new FakePrinter();
        kitchenDevice = new FakePrinter();

        PrinterConfig cashier = new PrinterConfig("Caja", PrinterConfig.Type.NETWORK);
        cashier.host = "127.0.0.1";
        cashier.port = cashierDevice.port();
        cashier.isDefault = true;

        PrinterConfig kitchen = new PrinterConfig("Cocina Caliente", PrinterConfig.Type.NETWORK);
        kitchen.slug = "kitchen";
        kitchen.host = "127.0.0.1";
        kitchen.port = kitchenDevice.port();

        config.printers.add(cashier);
        config.printers.add(kitchen);

        server = new ApiServer(config, new PrinterManager());
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop();
        }
        if (cashierDevice != null) {
            cashierDevice.close();
        }
        if (kitchenDevice != null) {
            kitchenDevice.close();
        }
    }

    /**
     * A stand-in for a networked ESC/POS printer: accepts one connection and keeps
     * whatever bytes were sent to it, so a test can assert which device a print job
     * actually reached.
     */
    private static class FakePrinter implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final Thread acceptor;

        FakePrinter() throws IOException {
            serverSocket = new ServerSocket(0);
            acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept();
                         InputStream in = socket.getInputStream()) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            synchronized (received) {
                                received.write(buffer, 0, read);
                            }
                        }
                    } catch (IOException e) {
                        return; // socket closed, test is done
                    }
                }
            }, "fake-printer");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String bytesReceived() {
            synchronized (received) {
                return received.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }

        /** Waits up to 3s for the job to arrive — escpos-coffee writes TCP asynchronously. */
        String awaitText(String expected) throws InterruptedException {
            for (int i = 0; i < 60; i++) {
                if (bytesReceived().contains(expected)) {
                    break;
                }
                Thread.sleep(50);
            }
            return bytesReceived();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bareHelloStillWorks() throws Exception {
        HttpResponse<String> response = get("/hw_proxy/hello");
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void helloOnAConfiguredRouteWorks() throws Exception {
        assertEquals(200, get("/kitchen/hw_proxy/hello").statusCode());
        assertEquals(200, get("/caja/hw_proxy/hello").statusCode());
    }

    @Test
    void helloOnAnUnknownRouteIs404() throws Exception {
        HttpResponse<String> response = get("/pastry/hw_proxy/hello");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("unknown printer route"));
    }

    @Test
    void statusJsonReportsOnlyTheRoutedPrinter() throws Exception {
        String all = get("/hw_proxy/status_json").body();
        assertTrue(all.contains("Caja"));
        assertTrue(all.contains("Cocina Caliente"));

        String kitchenOnly = get("/kitchen/hw_proxy/status_json").body();
        assertTrue(kitchenOnly.contains("Cocina Caliente"));
        assertTrue(kitchenOnly.contains("\"route\":\"kitchen\""));
        assertTrue(!kitchenOnly.contains("Caja"), "routed status must not include other printers");
    }

    @Test
    void statusJsonOnUnknownRouteSaysSo() throws Exception {
        String body = get("/pastry/hw_proxy/status_json").body();
        assertTrue(body.contains("Unknown printer route"));
    }

    @Test
    void printOnUnknownRouteFailsInsteadOfUsingTheDefaultPrinter() throws Exception {
        HttpResponse<String> response = post("/pastry/hw_proxy/print_receipt",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"receipt\":\"<div>hi</div>\"}}");
        assertEquals(200, response.statusCode()); // JSON-RPC reports failures in the body
        assertTrue(response.body().contains("Unknown printer route"), response.body());
    }

    @Test
    void printOnAKnownRouteReachesOnlyThatPrinter() throws Exception {
        HttpResponse<String> response = post("/kitchen/hw_proxy/print_receipt",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"receipt\":\"<div class=pos-receipt>KITCHEN ORDER</div>\"}}");
        assertTrue(response.body().contains("\"result\":true"), response.body());

        assertTrue(kitchenDevice.awaitText("KITCHEN ORDER").contains("KITCHEN ORDER"),
                "kitchen printer should have received the job");
        assertTrue(cashierDevice.bytesReceived().isEmpty(),
                "default printer must stay untouched when the route names another printer");
    }

    @Test
    void printWithoutARouteStillGoesToTheDefaultPrinter() throws Exception {
        post("/hw_proxy/print_receipt",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"receipt\":\"<div class=pos-receipt>CASHIER COPY</div>\"}}");

        assertTrue(cashierDevice.awaitText("CASHIER COPY").contains("CASHIER COPY"),
                "default printer should have received the job");
        assertTrue(kitchenDevice.bytesReceived().isEmpty(),
                "kitchen printer must not receive jobs addressed to the default route");
    }

    @Test
    void accentedPrinterNameIsReachableByItsSlug() throws Exception {
        // "Cocina Caliente" has the explicit slug "kitchen"; "Caja" has none, so its
        // route is derived from the name.
        post("/caja/hw_proxy/print_receipt",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"receipt\":\"<div class=pos-receipt>BY NAME</div>\"}}");

        assertTrue(cashierDevice.awaitText("BY NAME").contains("BY NAME"));
        assertTrue(kitchenDevice.bytesReceived().isEmpty());
    }

    @Test
    void openCashboxUsesTheRoutedPrinter() throws Exception {
        post("/kitchen/hw_proxy/open_cashbox", "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{}}");

        // ESC p 0 -> 0x1B 0x70 0x00 is the drawer-kick sequence.
        assertTrue(kitchenDevice.awaitText("\u001Bp").contains("\u001Bp"),
                "drawer pulse should reach the kitchen printer");
        assertTrue(cashierDevice.bytesReceived().isEmpty());
    }

    @Test
    void unknownEndpointIs404() throws Exception {
        assertEquals(404, get("/hw_proxy/nope").statusCode());
        assertEquals(404, get("/").statusCode());
    }

    @Test
    void handshakeAndNoOpsAnswerOnAnyRoute() throws Exception {
        assertTrue(post("/kitchen/hw_proxy/handshake", "{\"params\":{}}").body().contains("connected"));
        assertTrue(post("/kitchen/hw_proxy/take_control", "{\"params\":{}}").body().contains("OWNER"));
    }
}
