package com.jposbox.server.handlers;

import com.google.gson.Gson;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /hw_proxy/status_json -> describes configured printers and their reachability.
 *
 * <p>With a printer route in the path (/kitchen/hw_proxy/status_json) only that
 * printer is reported, so each Odoo pos.printer sees the state of its own device
 * rather than of every printer on this box.
 */
public class StatusJsonHandler implements HttpHandler {

    private static final Gson GSON = new Gson();

    private final PrinterRouter router;
    private final PrinterManager printerManager;

    public StatusJsonHandler(PrinterRouter router, PrinterManager printerManager) {
        this.router = router;
        this.printerManager = printerManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        PrinterRouter.Route route = PrinterRouter.parse(exchange);
        Map<String, Object> result = new LinkedHashMap<>();

        List<PrinterConfig> reported;
        if (route != null && route.hasPrinterKey()) {
            var match = router.config().getPrinterByRoute(route.printerKey());
            if (match.isEmpty()) {
                result.put("status", "disconnected");
                result.put("message", "Unknown printer route: " + route.printerKey());
                result.put("printers", Map.of());
                send(exchange, result);
                return;
            }
            reported = List.of(match.get());
        } else {
            reported = router.config().printers;
        }

        Map<String, Object> printers = new LinkedHashMap<>();
        for (PrinterConfig printer : reported) {
            boolean connected = printerManager.testConnection(printer);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", printer.type.toString());
            info.put("route", printer.routeSlug());
            info.put("default", printer.isDefault);
            info.put("connected", connected);
            printers.put(printer.name, info);
        }

        // "status" describes the proxy itself, which is obviously up if we got here;
        // per-printer reachability is reported in printers[].connected.
        result.put("status", "connected");
        result.put("printers", printers);
        send(exchange, result);
    }

    private void send(HttpExchange exchange, Map<String, Object> result) throws IOException {
        byte[] body = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
