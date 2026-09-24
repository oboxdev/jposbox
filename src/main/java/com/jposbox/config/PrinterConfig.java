package com.jposbox.config;

import java.text.Normalizer;
import java.util.Locale;

public class PrinterConfig {

    public enum Type {
        NETWORK, // raw TCP/IP ESC/POS, e.g. port 9100
        SYSTEM   // OS-registered printer (USB/driver), via javax.print
    }

    public long id = 0;
    public String name;
    public Type type = Type.NETWORK;
    public boolean isDefault = false;

    /**
     * Route key Odoo uses to address this printer, e.g. "kitchen" makes
     * /kitchen/hw_proxy/print_receipt print here. Blank means derive it from
     * {@link #name}.
     */
    public String slug;

    // NETWORK
    public String host;
    public int port = 9100;

    // SYSTEM
    public String systemPrinterName;

    // behaviour
    public boolean cutAfterPrint = true;
    public boolean openDrawerAfterPrint = false;
    public int charWidth = 42; // 80mm paper, font A default
    public int printerWidthPx = 576; // raster image width: 576 = 80mm, 384 = 58mm

    public PrinterConfig() {
    }

    public PrinterConfig(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    /** The normalised key this printer answers to in request paths. */
    public String routeSlug() {
        String source = (slug == null || slug.isBlank()) ? name : slug;
        return slugify(source);
    }

    /**
     * Normalises a printer name or route key into a URL-safe segment:
     * lowercased, accents stripped, non-alphanumerics collapsed into dashes.
     * "Cocina Caliente" and "cocina-caliente" both yield "cocina-caliente".
     */
    public static String slugify(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    @Override
    public String toString() {
        return name + " (" + type + (type == Type.NETWORK ? " " + host + ":" + port : " " + systemPrinterName) + ")";
    }
}
