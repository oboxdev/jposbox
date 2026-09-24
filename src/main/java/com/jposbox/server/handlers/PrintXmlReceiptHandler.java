package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

/**
 * jiotbox-style compat endpoint: POST /hw_proxy/print_xml_receipt {"receipt": "&lt;...&gt;"}.
 * The XML/HTML is stripped to plain text lines and printed as-is (no styling),
 * on the printer named by the route or on the default one.
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
        Document doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        String text = doc.wholeText();

        PrinterConfig printer = router.resolve(exchange);

        printerManager.print(printer, escpos -> {
            escpos.initializePrinter();
            for (String line : text.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    escpos.writeLF(line.trim());
                }
            }
        });

        return true;
    }
}
