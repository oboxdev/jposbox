package com.jposbox.server.handlers;

import com.jposbox.tls.SelfSignedCert;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * GET /ca.crt -> the public jPosBox CA certificate, so tablets/other PCs on the
 * LAN can install it as a trusted root. Public data only (the CA's private key
 * never leaves ca.p12). Served on plain HTTP too, since a device that doesn't
 * trust the CA yet can't fetch it over HTTPS without a warning; the SHA-256
 * fingerprint shown in the jPosBox window lets the user verify the download.
 */
public class CaCertHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] body;
        try {
            body = Files.readAllBytes(SelfSignedCert.ensureCaCertFile());
        } catch (Exception e) {
            byte[] msg = ("CA certificate unavailable: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(500, msg.length);
            try (var os = exchange.getResponseBody()) {
                os.write(msg);
            }
            return;
        }

        var headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/x-x509-ca-cert");
        headers.add("Content-Disposition", "attachment; filename=\"jposbox-ca.crt\"");
        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
