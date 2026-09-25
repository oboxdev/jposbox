package com.jposbox.server.handlers;

import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GET /hw_proxy/hello -> "ok" (used by Odoo POS to detect the proxy).
 * When the path carries a printer route (/kitchen/hw_proxy/hello) that no
 * configured printer answers to, this replies 404 so the misconfiguration shows
 * up in Odoo instead of silently pretending the printer exists.
 */
public class HelloHandler implements HttpHandler {

    private final PrinterRouter router;

    public HelloHandler(PrinterRouter router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        PrinterRouter.Route route = PrinterRouter.parse(exchange);
        boolean unknownRoute = route != null && route.hasPrinterKey()
                && router.config().getPrinterByRoute(route.printerKey()).isEmpty();

        int status = unknownRoute ? 404 : 200;
        String message = unknownRoute ? "unknown printer route: " + route.printerKey() : "ok";

        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
