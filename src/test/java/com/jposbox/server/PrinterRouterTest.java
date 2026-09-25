package com.jposbox.server;

import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrinterRouterTest {

    @Test
    void bareProxyPathHasNoPrinterKey() {
        PrinterRouter.Route route = PrinterRouter.parse("/hw_proxy/print_receipt", null);
        assertEquals("print_receipt", route.endpoint());
        assertFalse(route.hasPrinterKey());
    }

    @Test
    void prefixFormIsWhatOdooProduces() {
        // proxy_ip = "192.168.1.50:8008/kitchen" -> Odoo appends "/hw_proxy/<endpoint>"
        PrinterRouter.Route route = PrinterRouter.parse("/kitchen/hw_proxy/default_printer_action", null);
        assertEquals("default_printer_action", route.endpoint());
        assertEquals("kitchen", route.printerKey());
    }

    @Test
    void infixFormAlsoSelectsAPrinter() {
        PrinterRouter.Route route = PrinterRouter.parse("/hw_proxy/bar/print_receipt", null);
        assertEquals("print_receipt", route.endpoint());
        assertEquals("bar", route.printerKey());
    }

    @Test
    void onlyTheSegmentNextToHwProxyCounts() {
        PrinterRouter.Route route = PrinterRouter.parse("/jposbox/printers/kitchen/hw_proxy/open_cashbox", null);
        assertEquals("open_cashbox", route.endpoint());
        assertEquals("kitchen", route.printerKey());
    }

    @Test
    void queryParameterOverridesThePath() {
        PrinterRouter.Route route = PrinterRouter.parse("/kitchen/hw_proxy/print_receipt", "printer=bar");
        assertEquals("bar", route.printerKey());
    }

    @Test
    void nonProxyPathsAreNotRoutes() {
        assertNull(PrinterRouter.parse("/", null));
        assertNull(PrinterRouter.parse("/favicon.ico", null));
        assertNull(PrinterRouter.parse("/hw_proxy", null));
        assertNull(PrinterRouter.parse("/hw_proxy/", null));
    }

    @Test
    void slugifyNormalisesAccentsCaseAndSeparators() {
        assertEquals("cocina-caliente", PrinterConfig.slugify("Cocina Caliente"));
        assertEquals("cocina-caliente", PrinterConfig.slugify("COCINA_CALIENTE"));
        assertEquals("panaderia", PrinterConfig.slugify("Panadería"));
        assertEquals("caja-1", PrinterConfig.slugify("  Caja #1  "));
        assertEquals("", PrinterConfig.slugify(null));
    }

    @Test
    void routeKeyMatchesNameWhenNoExplicitSlug() {
        AppConfig config = new AppConfig();
        PrinterConfig kitchen = new PrinterConfig("Cocina Caliente", PrinterConfig.Type.NETWORK);
        config.printers.add(kitchen);

        assertTrue(config.getPrinterByRoute("cocina-caliente").isPresent());
        assertTrue(config.getPrinterByRoute("Cocina Caliente").isPresent());
        assertTrue(config.getPrinterByRoute("nope").isEmpty());
    }

    @Test
    void explicitSlugWinsOverName() {
        AppConfig config = new AppConfig();
        PrinterConfig kitchen = new PrinterConfig("Cocina Caliente", PrinterConfig.Type.NETWORK);
        kitchen.slug = "kitchen";
        config.printers.add(kitchen);

        assertTrue(config.getPrinterByRoute("kitchen").isPresent());
        assertTrue(config.getPrinterByRoute("cocina-caliente").isEmpty());
    }

    @Test
    void duplicateRoutesAreReported() {
        AppConfig config = new AppConfig();
        config.printers.add(new PrinterConfig("Bar", PrinterConfig.Type.NETWORK));
        PrinterConfig clash = new PrinterConfig("bar", PrinterConfig.Type.NETWORK);
        config.printers.add(clash);

        assertEquals(java.util.List.of("bar"), config.duplicateRouteSlugs());
    }
}
