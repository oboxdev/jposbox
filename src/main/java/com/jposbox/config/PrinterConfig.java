package com.jposbox.config;

public class PrinterConfig {

    public enum Type {
        NETWORK, // raw TCP/IP ESC/POS, e.g. port 9100
        SYSTEM   // OS-registered printer (USB/driver), via javax.print
    }

    public long id = 0;
    public String name;
    public Type type = Type.NETWORK;
    public boolean isDefault = false;

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

    @Override
    public String toString() {
        return name + " (" + type + (type == Type.NETWORK ? " " + host + ":" + port : " " + systemPrinterName) + ")";
    }
}
