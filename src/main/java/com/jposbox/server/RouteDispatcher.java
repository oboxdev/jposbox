package com.jposbox.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Single entry point for every request: extracts the hw_proxy endpoint from the
 * path (wherever the printer route key sits, see {@link PrinterRouter}) and
 * delegates to the handler registered for it.
 */
public class RouteDispatcher implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(RouteDispatcher.class.getName());

    private final Map<String, HttpHandler> endpoints;

    public RouteDispatcher(Map<String, HttpHandler> endpoints) {
        this.endpoints = endpoints;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        PrinterRouter.Route route = PrinterRouter.parse(exchange);
        HttpHandler handler = route == null ? null : endpoints.get(route.endpoint());
        if (handler == null) {
            LOG.fine("No handler for " + exchange.getRequestURI());
            sendNotFound(exchange);
            return;
        }
        handler.handle(exchange);
    }

    private void sendNotFound(HttpExchange exchange) throws IOException {
        byte[] body = ("Not found: " + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(404, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
