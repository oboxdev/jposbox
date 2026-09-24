package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.printer.ReceiptRenderer;
import com.jposbox.server.JsonRpcHandler;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * POST /hw_proxy/print_receipt {"receipt": "&lt;div class=pos-receipt&gt;...&lt;/div&gt;"}
 * Prints on the printer named by the route (e.g. /kitchen/hw_proxy/print_receipt),
 * or on the default printer when the path carries no route key.
 */
public class PrintReceiptHandler extends JsonRpcHandler {

    private final PrinterRouter router;
    private final PrinterManager printerManager;

    public PrintReceiptHandler(PrinterRouter router, PrinterManager printerManager) {
        this.router = router;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params, HttpExchange exchange) throws IOException {
        if (!params.has("receipt")) {
            throw new IllegalArgumentException("Missing 'receipt' parameter");
        }
        String html = params.get("receipt").getAsString();

        PrinterConfig printer = router.resolve(exchange);

        ReceiptRenderer renderer = new ReceiptRenderer(printer.charWidth);
        printerManager.print(printer, escpos -> {
            escpos.initializePrinter();
            renderer.render(escpos, html);
        });

        return true;
    }
}
