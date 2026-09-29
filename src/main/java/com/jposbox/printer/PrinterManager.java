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
import javax.print.attribute.standard.PrinterIsAcceptingJobs;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a {@link PrinterConfig} into a connection and sends ESC/POS bytes to it.
 *
 * <p>Jobs targeting the same physical device are serialized (see
 * {@link #lockFor}): the HTTP server handles requests on a cached, unbounded
 * thread pool, so two print calls that arrive close together (a kitchen
 * ticket and a receipt fired one after another, or a busy POS printing
 * several tickets in a row) can otherwise open <em>concurrent</em> TCP
 * connections to the very same printer. Most budget ESC/POS printers only
 * expect one active connection and can interleave or drop bytes from two at
 * once — indistinguishable, from the printout, from a garbled encoding bug.
 */
public class PrinterManager {

    private static final Logger LOG = Logger.getLogger(PrinterManager.class.getName());
    private final ConcurrentHashMap<String, Lock> printerLocks = new ConcurrentHashMap<>();

    /** Lists OS-registered printers (used for SYSTEM/USB type configuration). */
    public static List<String> listSystemPrinters() {
        List<String> names = new ArrayList<>();
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            names.add(service.getName());
        }
        return names;
    }

    /**
     * The lock guarding the physical device this printer resolves to (keyed
     * by destination, not by config row id, so two differently-named
     * {@link PrinterConfig} entries that happen to point at the same
     * host:port or OS printer still serialize against each other).
     */
    private Lock lockFor(PrinterConfig printer) {
        String key = switch (printer.type) {
            case NETWORK -> "network:" + printer.host + ":" + printer.port;
            case SYSTEM -> "system:" + printer.systemPrinterName;
        };
        return printerLocks.computeIfAbsent(key, k -> new ReentrantLock());
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
        Lock lock = lockFor(printer);
        lock.lock();
        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            writer.write(escpos);
            boolean mechanicalOp = false;
            if (printer.cutAfterPrint) {
                escpos.feed(3).cut(CutMode.PART);
                mechanicalOp = true;
            }
            if (printer.openDrawerAfterPrint) {
                pulseDrawer(escpos);
                mechanicalOp = true;
            }
            escpos.flush();
            if (mechanicalOp) {
                settleAfterMechanicalOp(printer);
            }
        } finally {
            lock.unlock();
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

        Lock lock = lockFor(printer);
        lock.lock();
        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            escpos.initializePrinter();
            writeRasterImage(escpos, source, printer.printerWidthPx);
            boolean mechanicalOp = false;
            if (printer.cutAfterPrint) {
                escpos.feed(3).cut(CutMode.PART);
                mechanicalOp = true;
            }
            if (printer.openDrawerAfterPrint) {
                pulseDrawer(escpos);
                mechanicalOp = true;
            }
            escpos.flush();
            if (mechanicalOp) {
                settleAfterMechanicalOp(printer);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encodes a bitmap as ESC/POS raster data and writes it into an already-open
     * job (e.g. an {@code <img>} embedded mid-receipt by {@link JiotBoxXmlRenderer}),
     * scaling it to the printer's raster width first. Shared with {@link #printImage}
     * so both paths encode images identically.
     */
    public static void writeRasterImage(EscPos escpos, BufferedImage image, int targetWidthPx) throws IOException {
        BufferedImage scaled = scaleToWidth(image, targetWidthPx);
        EscPosImage escPosImage = new EscPosImage(new CoffeeImageImpl(scaled), new BitonalThreshold());
        escpos.write(new RasterBitImageWrapper(), escPosImage);
    }

    private static BufferedImage scaleToWidth(BufferedImage source, int targetWidth) {
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
        Lock lock = lockFor(printer);
        lock.lock();
        try (OutputStream out = openConnection(printer)) {
            EscPos escpos = new EscPos(out);
            pulseDrawer(escpos);
            escpos.flush();
            settleAfterMechanicalOp(printer);
        } finally {
            lock.unlock();
        }
    }

    private void pulseDrawer(EscPos escpos) throws IOException {
        try {
            escpos.pulsePin(PinConnector.Pin_2, 25, 250);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Failed to pulse cash drawer pin", e);
        }
    }

    /**
     * Waits {@link PrinterConfig#postCutDelayMs} before the caller's
     * try-with-resources closes the connection, giving the printer's cutter
     * blade / drawer solenoid time to finish before the next job's connection
     * can possibly arrive. See the field's javadoc for why this matters.
     */
    private void settleAfterMechanicalOp(PrinterConfig printer) {
        if (printer.postCutDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(printer.postCutDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Connectivity check used by status_json. For NETWORK printers this opens and
     * immediately closes a TCP connection. For SYSTEM printers it only looks up the
     * PrintService and checks its status — opening a PrinterOutputStream creates a
     * job in the OS print queue, which would saturate the queue on frequent polling.
     */
    public boolean testConnection(PrinterConfig printer) {
        if (printer.type == PrinterConfig.Type.SYSTEM) {
            PrintService service = PrinterOutputStream.getPrintServiceByName(printer.systemPrinterName);
            if (service == null) {
                return false;
            }
            PrinterIsAcceptingJobs accepting =
                    (PrinterIsAcceptingJobs) service.getAttribute(PrinterIsAcceptingJobs.class);
            return accepting == null || accepting == PrinterIsAcceptingJobs.ACCEPTING_JOBS;
        }
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
