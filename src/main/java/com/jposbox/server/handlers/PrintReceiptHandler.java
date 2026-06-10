package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.printer.ReceiptRenderer;
import com.jposbox.server.JsonRpcHandler;

import java.io.IOException;

/** POST /hw_proxy/print_receipt {"receipt": "&lt;div class=pos-receipt&gt;...&lt;/div&gt;"} */
public class PrintReceiptHandler extends JsonRpcHandler {

    private final AppConfig config;
    private final PrinterManager printerManager;

    public PrintReceiptHandler(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params) throws IOException {
        if (!params.has("receipt")) {
            throw new IllegalArgumentException("Missing 'receipt' parameter");
        }
        String html = params.get("receipt").getAsString();

        PrinterConfig printer = config.getDefaultPrinter()
                .orElseThrow(() -> new IllegalStateException("No printer configured"));

        ReceiptRenderer renderer = new ReceiptRenderer(printer.charWidth);
        printerManager.print(printer, escpos -> {
            escpos.initializePrinter();
            renderer.render(escpos, html);
        });

        return true;
    }
}
