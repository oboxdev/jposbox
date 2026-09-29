package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.JiotBoxXmlRenderer;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * jiotbox-style compat endpoint: POST /hw_proxy/print_xml_receipt {"receipt": "&lt;...&gt;"}.
 * The XML is rendered as ESC/POS via {@link JiotBoxXmlRenderer} (the legacy
 * jIotBox tag set: receipt/img/br/hr/textnormal/textdoublewidth/
 * textdoubleheight/div/line/left/right/table/qrcode/cut/barcode, plus
 * font="a"|"b" and align="left"|"center"|"right" attributes), on the printer
 * named by the route or on the default one.
 */
public class PrintXmlReceiptHandler extends JsonRpcHandler {

    private final PrinterRouter router;
    private final PrinterManager printerManager;

    public PrintXmlReceiptHandler(PrinterRouter router, PrinterManager printerManager) {
        this.router = router;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params, HttpExchange exchange) throws IOException {
        if (!params.has("receipt")) {
            throw new IllegalArgumentException("Missing 'receipt' parameter");
        }
        String xml = params.get("receipt").getAsString();

        PrinterConfig printer = router.resolve(exchange);

        JiotBoxXmlRenderer renderer = new JiotBoxXmlRenderer(printer.charWidth, printer.printerWidthPx);
        printerManager.print(printer, escpos -> {
            escpos.initializePrinter();
            renderer.render(escpos, xml);
        });

        return true;
    }
}
