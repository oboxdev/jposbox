package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.JiotBoxXmlRenderer;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * POST /hw_proxy/default_printer_action
 * Used by Odoo 17-19 POS: {"params":{"data":{"action":"print_receipt","receipt":"&lt;base64 payload&gt;"}}}
 * Normally the payload is a rasterized image (JPEG/PNG) of the ticket, printed
 * as a bitmap. Some integrations (a legacy jIotBox-style client) instead send
 * plain XML through this same base64 field; when the decoded bytes don't
 * decode as an image, they're sniffed as text and rendered via
 * {@link JiotBoxXmlRenderer} instead of failing.
 *
 * <p>Despite the endpoint name, the target is the printer named by the route
 * (/kitchen/hw_proxy/default_printer_action), falling back to the default printer.
 */
public class DefaultPrinterActionHandler extends JsonRpcHandler {

    private static final Logger LOG = Logger.getLogger(DefaultPrinterActionHandler.class.getName());

    private final PrinterRouter router;
    private final PrinterManager printerManager;

    public DefaultPrinterActionHandler(PrinterRouter router, PrinterManager printerManager) {
        this.router = router;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params, HttpExchange exchange) throws IOException {
        if (!params.has("data") || !params.get("data").isJsonObject()) {
            throw new IllegalArgumentException("Missing 'data' parameter");
        }
        JsonObject data = params.getAsJsonObject("data");
        String action = data.has("action") ? data.get("action").getAsString() : "";

        PrinterConfig printer = router.resolve(exchange);

        switch (action) {
            case "print_receipt": {
                if (!data.has("receipt")) {
                    throw new IllegalArgumentException("Missing 'receipt' parameter");
                }
                byte[] decoded = Base64.getDecoder().decode(data.get("receipt").getAsString());
                if (ImageIO.read(new ByteArrayInputStream(decoded)) != null) {
                    printerManager.printImage(printer, decoded);
                } else {
                    printXml(printer, decoded);
                }
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

    /** Renders a base64 payload that didn't decode as an image, as jIotBox-style XML. */
    private void printXml(PrinterConfig printer, byte[] decoded) throws IOException {
        String text = new String(decoded, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("<")) {
            throw new IllegalArgumentException(
                    "'receipt' is neither a decodable image nor XML text (payload starts with: "
                            + text.substring(0, Math.min(20, text.length())) + ")");
        }
        JiotBoxXmlRenderer renderer = new JiotBoxXmlRenderer(printer.charWidth, printer.printerWidthPx);
        printerManager.print(printer, escpos -> {
            escpos.initializePrinter();
            renderer.render(escpos, text);
        });
    }
}
