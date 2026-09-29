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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a live ApiServer to confirm the legacy jIotBox XML tag set actually
 * reaches a printer end to end, through both entry points: the dedicated
 * print_xml_receipt endpoint, and default_printer_action's base64 field when
 * it turns out to hold XML text rather than an image.
 */
class JiotBoxXmlEndToEndTest {

    private ApiServer server;
    private int port;
    private FakePrinter device;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        AppConfig config = new AppConfig();
        config.httpsEnabled = false;
        config.httpPort = freePort();
        this.port = config.httpPort;

        device = new FakePrinter();
        PrinterConfig printer = new PrinterConfig("Caja", PrinterConfig.Type.NETWORK);
        printer.host = "127.0.0.1";
        printer.port = device.port();
        printer.isDefault = true;
        printer.charWidth = 32;
        config.printers.add(printer);

        server = new ApiServer(config, new PrinterManager());
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop();
        }
        if (device != null) {
            device.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void printXmlReceiptRendersJiotBoxTags() throws Exception {
        String xml = "<receipt><div align=\"center\" font=\"b\">STORE NAME</div>"
                + "<hr/><table><tr><td width=\"0.6\">Coffee x2</td>"
                + "<td width=\"0.4\" align=\"right\">10.00</td></tr></table><cut/></receipt>";
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"receipt\":\""
                + xml.replace("\"", "\\\"") + "\"}}";

        HttpResponse<String> response = post("/hw_proxy/print_xml_receipt", json);
        assertTrue(response.body().contains("\"result\":true"), response.body());

        String received = device.awaitText("STORE NAME");
        assertTrue(received.contains("STORE NAME"), received);
        assertTrue(received.contains("Coffee x2"), received);
    }

    @Test
    void defaultPrinterActionSniffsBase64XmlAndRendersIt() throws Exception {
        String xml = "<receipt>Order #42<br/><barcode>ORDER0042</barcode></receipt>";
        String base64 = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"data\":{\"action\":\"print_receipt\",\"receipt\":\""
                + base64 + "\"}}}";

        HttpResponse<String> response = post("/hw_proxy/default_printer_action", json);
        assertTrue(response.body().contains("\"result\":true"), response.body());

        String received = device.awaitText("ORDER0042");
        assertTrue(received.contains("Order #42"), received);
        assertTrue(received.contains("ORDER0042"), received);
    }

    @Test
    void defaultPrinterActionStillHandlesRealImages() throws Exception {
        // A base64 payload that IS a valid image must still take the raster path,
        // not get misdetected as text.
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 40, 20);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        String base64 = Base64.getEncoder().encodeToString(png.toByteArray());
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"data\":{\"action\":\"print_receipt\",\"receipt\":\""
                + base64 + "\"}}}";

        HttpResponse<String> response = post("/hw_proxy/default_printer_action", json);
        assertTrue(response.body().contains("\"result\":true"), response.body());

        // The raster path writes binary image bytes, nothing textual to assert on;
        // just confirm SOME data reached the device (the image path, not an error).
        for (int i = 0; i < 60 && device.bytesReceived().length == 0; i++) {
            Thread.sleep(50);
        }
        assertTrue(device.bytesReceived().length > 20, "expected raster bytes to reach the device");
    }

    @Test
    void garbageBase64PayloadFailsWithAClearError() throws Exception {
        String base64 = Base64.getEncoder().encodeToString("not xml, not an image".getBytes(StandardCharsets.UTF_8));
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"data\":{\"action\":\"print_receipt\",\"receipt\":\""
                + base64 + "\"}}}";

        HttpResponse<String> response = post("/hw_proxy/default_printer_action", json);
        assertTrue(response.body().contains("error"), response.body());
        assertTrue(response.body().contains("neither a decodable image nor XML"), response.body());
    }

    /** A stand-in for a networked ESC/POS printer: accepts one connection, keeps the bytes sent to it. */
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
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            synchronized (received) {
                                received.write(buffer, 0, read);
                            }
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-printer");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        byte[] bytesReceived() {
            synchronized (received) {
                return received.toByteArray();
            }
        }

        String awaitText(String expected) throws InterruptedException {
            String s = "";
            for (int i = 0; i < 60; i++) {
                s = new String(bytesReceived(), StandardCharsets.ISO_8859_1);
                if (s.contains(expected)) {
                    break;
                }
                Thread.sleep(50);
            }
            return s;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
