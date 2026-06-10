package com.jposbox.server.handlers;

import com.google.gson.Gson;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /hw_proxy/status_json -> describes configured printers and their reachability. */
public class StatusJsonHandler implements HttpHandler {

    private static final Gson GSON = new Gson();

    private final AppConfig config;
    private final PrinterManager printerManager;

    public StatusJsonHandler(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, Object> printers = new LinkedHashMap<>();
        for (PrinterConfig printer : config.printers) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", printer.type.toString());
            info.put("default", printer.isDefault);
            info.put("connected", printerManager.testConnection(printer));
            printers.put(printer.name, info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "connected");
        result.put("printers", printers);

        byte[] body = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
