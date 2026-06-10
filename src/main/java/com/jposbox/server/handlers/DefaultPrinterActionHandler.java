package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * POST /hw_proxy/default_printer_action
 * Used by Odoo 19 POS: {"params":{"data":{"action":"print_receipt","receipt":"&lt;base64 image&gt;"}}}
 * The receipt is a rasterized image (JPEG/PNG) of the ticket, printed as a bitmap.
 */
public class DefaultPrinterActionHandler extends JsonRpcHandler {

    private static final Logger LOG = Logger.getLogger(DefaultPrinterActionHandler.class.getName());

    private final AppConfig config;
    private final PrinterManager printerManager;

    public DefaultPrinterActionHandler(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params) throws IOException {
        if (!params.has("data") || !params.get("data").isJsonObject()) {
            throw new IllegalArgumentException("Missing 'data' parameter");
        }
        JsonObject data = params.getAsJsonObject("data");
        String action = data.has("action") ? data.get("action").getAsString() : "";

        PrinterConfig printer = config.getDefaultPrinter()
                .orElseThrow(() -> new IllegalStateException("No printer configured"));

        switch (action) {
            case "print_receipt": {
                if (!data.has("receipt")) {
                    throw new IllegalArgumentException("Missing 'receipt' parameter");
                }
                byte[] imageBytes = Base64.getDecoder().decode(data.get("receipt").getAsString());
                printerManager.printImage(printer, imageBytes);
                return true;
            }
            case "open_cashbox":
            case "cashbox":
                printerManager.openDrawer(printer);
                return true;
            default:
                LOG.warning("Unhandled default_printer_action: " + action);
                return true;
        }
    }
}
