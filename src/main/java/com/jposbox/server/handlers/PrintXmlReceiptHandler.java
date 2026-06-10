package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

/**
 * jiotbox-style compat endpoint: POST /hw_proxy/print_xml_receipt {"receipt": "&lt;...&gt;"}.
 * The XML/HTML is stripped to plain text lines and printed as-is (no styling).
 */
public class PrintXmlReceiptHandler extends JsonRpcHandler {

    private final AppConfig config;
    private final PrinterManager printerManager;

    public PrintXmlReceiptHandler(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params) throws IOException {
        if (!params.has("receipt")) {
            throw new IllegalArgumentException("Missing 'receipt' parameter");
        }
        String xml = params.get("receipt").getAsString();
        Document doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        String text = doc.wholeText();

        PrinterConfig printer = config.getDefaultPrinter()
                .orElseThrow(() -> new IllegalStateException("No printer configured"));

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
