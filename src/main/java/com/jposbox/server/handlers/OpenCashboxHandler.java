package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;

import java.io.IOException;

/** POST /hw_proxy/open_cashbox -> pulses the drawer-kick pin on the default printer. */
public class OpenCashboxHandler extends JsonRpcHandler {

    private final AppConfig config;
    private final PrinterManager printerManager;

    public OpenCashboxHandler(AppConfig config, PrinterManager printerManager) {
        this.config = config;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params) throws IOException {
        PrinterConfig printer = config.getDefaultPrinter()
                .orElseThrow(() -> new IllegalStateException("No printer configured"));
        printerManager.openDrawer(printer);
        return true;
    }
}
