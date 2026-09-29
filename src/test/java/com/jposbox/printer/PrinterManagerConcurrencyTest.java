package com.jposbox.printer;

import com.jposbox.config.PrinterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies two fixes for garbled printouts after several jobs in a row:
 * 1. A settle delay after cut/drawer commands, before the connection closes
 *    (the printer's cutter/solenoid is mechanical, not instant).
 * 2. Jobs targeting the same physical printer are serialized, so the HTTP
 *    server's unbounded thread pool can't open two concurrent connections to
 *    one printer (many budget ESC/POS printers only handle one at a time).
 */
class PrinterManagerConcurrencyTest {

    private final PrinterManager pm = new PrinterManager();
    private final List<FakePrinter> devices = new ArrayList<>();

    @AfterEach
    void closeDevices() throws IOException {
        for (FakePrinter d : devices) {
            d.close();
        }
    }

    private FakePrinter newDevice() throws IOException {
        FakePrinter d = new FakePrinter();
        devices.add(d);
        return d;
    }

    private PrinterConfig networkPrinter(int port) {
        PrinterConfig p = new PrinterConfig("Test", PrinterConfig.Type.NETWORK);
        p.host = "127.0.0.1";
        p.port = port;
        p.cutAfterPrint = false;
        p.openDrawerAfterPrint = false;
        p.postCutDelayMs = 0;
        return p;
    }

    @Test
    void waitsPostCutDelayBeforeReturning() throws Exception {
        PrinterConfig printer = networkPrinter(newDevice().port());
        printer.cutAfterPrint = true;
        printer.postCutDelayMs = 300;

        long start = System.nanoTime();
        pm.print(printer, escpos -> {});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 280, "expected ~300ms settle delay, got " + elapsedMs + "ms");
    }

    @Test
    void noDelayWhenPostCutDelayIsZero() throws Exception {
        PrinterConfig printer = networkPrinter(newDevice().port());
        printer.cutAfterPrint = true;
        printer.postCutDelayMs = 0;

        long start = System.nanoTime();
        pm.print(printer, escpos -> {});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 200, "expected no settle delay when disabled, took " + elapsedMs + "ms");
    }

    @Test
    void noDelayWhenNothingMechanicalHappened() throws Exception {
        PrinterConfig printer = networkPrinter(newDevice().port());
        printer.cutAfterPrint = false;
        printer.openDrawerAfterPrint = false;
        printer.postCutDelayMs = 300; // configured, but nothing mechanical to settle after

        long start = System.nanoTime();
        pm.print(printer, escpos -> {});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 200, "no cut/drawer happened, should not have waited; took " + elapsedMs + "ms");
    }

    @Test
    void openDrawerAlsoWaitsPostCutDelay() throws Exception {
        PrinterConfig printer = networkPrinter(newDevice().port());
        printer.postCutDelayMs = 300;

        long start = System.nanoTime();
        pm.openDrawer(printer);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 280, "expected ~300ms settle delay after drawer pulse, got " + elapsedMs + "ms");
    }

    @Test
    void concurrentJobsToTheSamePrinterAreSerialized() throws Exception {
        PrinterConfig printer = networkPrinter(newDevice().port());
        long totalMs = runTwoConcurrentSlowJobs(printer, printer, 200);
        // Serialized: total wall time is close to the sum of both jobs (400ms),
        // not close to one job's duration (200ms) as it would be if they overlapped.
        assertTrue(totalMs >= 380, "expected jobs to run one after another (~400ms+), took " + totalMs + "ms");
    }

    @Test
    void concurrentJobsToDifferentPrintersAreNotSerializedAgainstEachOther() throws Exception {
        PrinterConfig printerA = networkPrinter(newDevice().port());
        PrinterConfig printerB = networkPrinter(newDevice().port());
        long totalMs = runTwoConcurrentSlowJobs(printerA, printerB, 200);
        // Different destinations: should overlap, total wall time close to one job's
        // duration (200ms), well under the ~400ms it would take if serialized too.
        assertTrue(totalMs < 350, "different printers should print in parallel, took " + totalMs + "ms");
    }

    /** Runs one slow (sleep-in-writer) job against each printer concurrently; returns total wall time in ms. */
    private long runTwoConcurrentSlowJobs(PrinterConfig printer1, PrinterConfig printer2, int sleepMs) throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();

        Runnable job1 = slowJob(printer1, sleepMs, bothReady, go, failure);
        Runnable job2 = slowJob(printer2, sleepMs, bothReady, go, failure);
        Thread t1 = new Thread(job1);
        Thread t2 = new Thread(job2);
        t1.start();
        t2.start();
        bothReady.await(); // make sure both threads are actually up before starting the clock

        long start = System.nanoTime();
        go.countDown();
        t1.join();
        t2.join();
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        if (failure.get() != null) {
            throw failure.get();
        }
        return totalMs;
    }

    private Runnable slowJob(PrinterConfig printer, int sleepMs, CountDownLatch ready, CountDownLatch go,
                              AtomicReference<Exception> failure) {
        return () -> {
            ready.countDown();
            try {
                go.await();
                pm.print(printer, escpos -> {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception e) {
                failure.set(e);
            }
        };
    }

    /** A stand-in networked ESC/POS printer: accepts connections, discards the bytes. */
    private static class FakePrinter implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptor;

        FakePrinter() throws IOException {
            serverSocket = new ServerSocket(0);
            acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept();
                         InputStream in = socket.getInputStream()) {
                        ByteArrayOutputStream sink = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            sink.write(buffer, 0, read);
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-printer");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
