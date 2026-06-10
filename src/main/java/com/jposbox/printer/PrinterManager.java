package com.jposbox.printer;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPos.CutMode;
import com.github.anastaciocintra.escpos.EscPos.PinConnector;
import com.github.anastaciocintra.escpos.image.BitonalThreshold;
import com.github.anastaciocintra.escpos.image.CoffeeImageImpl;
import com.github.anastaciocintra.escpos.image.EscPosImage;
import com.github.anastaciocintra.escpos.image.RasterBitImageWrapper;
import com.github.anastaciocintra.output.PrinterOutputStream;
import com.github.anastaciocintra.output.TcpIpOutputStream;
import com.jposbox.config.PrinterConfig;

import javax.imageio.ImageIO;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a {@link PrinterConfig} into a connection and sends ESC/POS bytes to it.
 */
public class PrinterManager {

    private static final Logger LOG = Logger.getLogger(PrinterManager.class.getName());

    /** Lists OS-registered printers (used for SYSTEM/USB type configuration). */
    public static List<String> listSystemPrinters() {
        List<String> names = new ArrayList<>();
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            names.add(service.getName());
        }
        return names;
    }

    private OutputStream openConnection(PrinterConfig printer) throws IOException {
        switch (printer.type) {
            case NETWORK:
                return new TcpIpOutputStream(printer.host, printer.port);
            case SYSTEM:
                PrintService service = PrinterOutputStream.getPrintServiceByName(printer.systemPrinterName);
                if (service == null) {
                    throw new IOException("System printer not found: " + printer.systemPrinterName);
                }
                return new PrinterOutputStream(service);
            default:
                throw new IOException("Unsupported printer type: " + printer.type);
        }
    }

    /** Sends raw bytes (already ESC/POS encoded) to the printer. */
    public void printRaw(PrinterConfig printer, byte[] data) throws IOException {
        try (OutputStream out = openConnection(printer)) {
            out.write(data);
            out.flush();
        }
    }

    /** Renders and prints a receipt built via the provided callback. */
    public void print(PrinterConfig printer, EscPosWriter writer) throws IOException {
        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            writer.write(escpos);
            if (printer.cutAfterPrint) {
                escpos.feed(3).cut(CutMode.PART);
            }
            if (printer.openDrawerAfterPrint) {
                pulseDrawer(escpos);
            }
            escpos.flush();
        }
    }

    /**
     * Prints a raster image (e.g. a receipt rendered to JPEG/PNG by the POS UI),
     * scaled to the printer's raster width and converted to monochrome.
     */
    public void printImage(PrinterConfig printer, byte[] imageBytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) {
            throw new IOException("Could not decode receipt image");
        }
        BufferedImage scaled = scaleToWidth(source, printer.printerWidthPx);

        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            escpos.initializePrinter();
            EscPosImage escPosImage = new EscPosImage(new CoffeeImageImpl(scaled), new BitonalThreshold());
            escpos.write(new RasterBitImageWrapper(), escPosImage);
            if (printer.cutAfterPrint) {
                escpos.feed(3).cut(CutMode.PART);
            }
            if (printer.openDrawerAfterPrint) {
                pulseDrawer(escpos);
            }
            escpos.flush();
        }
    }

    private BufferedImage scaleToWidth(BufferedImage source, int targetWidth) {
        if (source.getWidth() == targetWidth) {
            return source;
        }
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, targetWidth, targetHeight);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    /** Pulses the cash-drawer kick-out pin (standard ESC p 0 25 250). */
    public void openDrawer(PrinterConfig printer) throws IOException {
        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            pulseDrawer(escpos);
            escpos.flush();
        }
    }

    private void pulseDrawer(EscPos escpos) throws IOException {
        try {
            escpos.pulsePin(PinConnector.Pin_2, 25, 250);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Failed to pulse cash drawer pin", e);
        }
    }

    /** Simple connectivity test: open and immediately close the connection. */
    public boolean testConnection(PrinterConfig printer) {
        try (OutputStream out = openConnection(printer)) {
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Printer test failed for " + printer.name, e);
            return false;
        }
    }

    @FunctionalInterface
    public interface EscPosWriter {
        void write(EscPos escpos) throws IOException;
    }
}
