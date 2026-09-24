package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.JsonRpcHandler;
import com.jposbox.server.PrinterRouter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * POST /hw_proxy/open_cashbox -> pulses the drawer-kick pin on the printer named
 * by the route, or on the default printer.
 */
public class OpenCashboxHandler extends JsonRpcHandler {

    private final PrinterRouter router;
    private final PrinterManager printerManager;

    public OpenCashboxHandler(PrinterRouter router, PrinterManager printerManager) {
        this.router = router;
        this.printerManager = printerManager;
    }

    @Override
    protected Object process(JsonObject params, HttpExchange exchange) throws IOException {
        PrinterConfig printer = router.resolve(exchange);
        printerManager.openDrawer(printer);
        return true;
    }
}
