package com.jposbox.server;

import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out which printer a request targets, so one jPosBox can serve several
 * printers (receipt, kitchen, bar) from a single port.
 *
 * <p>Odoo builds its proxy URL by concatenating the {@code pos.printer}
 * {@code proxy_ip} with {@code "/hw_proxy/<endpoint>"}, so a proxy_ip of
 * {@code 192.168.1.50:8008/kitchen} arrives here as
 * {@code /kitchen/hw_proxy/print_receipt} — the route key lands <em>before</em>
 * {@code hw_proxy}. Both that prefix form and the infix form
 * {@code /hw_proxy/kitchen/print_receipt} select the "kitchen" printer, and a
 * {@code ?printer=kitchen} query parameter overrides both (handy for curl).
 * A bare {@code /hw_proxy/print_receipt} keeps hitting the default printer, so
 * existing single-printer setups are unaffected.
 */
public class PrinterRouter {

    private static final String PROXY_SEGMENT = "hw_proxy";

    private final AppConfig config;

    public PrinterRouter(AppConfig config) {
        this.config = config;
    }

    /** A parsed request: the endpoint being called and the route key, if any. */
    public record Route(String endpoint, String printerKey) {
        public boolean hasPrinterKey() {
            return printerKey != null && !printerKey.isBlank();
        }
    }

    /**
     * Splits a request URI into endpoint + route key, or returns null when the
     * path isn't a hw_proxy call at all.
     */
    public static Route parse(HttpExchange exchange) {
        return parse(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery());
    }

    static Route parse(String path, String rawQuery) {
        List<String> segments = new ArrayList<>();
        for (String segment : (path == null ? "" : path).split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }

        int proxyIndex = segments.lastIndexOf(PROXY_SEGMENT);
        if (proxyIndex < 0 || proxyIndex == segments.size() - 1) {
            return null; // no /hw_proxy/, or nothing after it
        }

        String endpoint;
        String printerKey = null;
        int after = segments.size() - proxyIndex - 1;
        if (after >= 2) {
            // infix form: /hw_proxy/<printer>/<endpoint>
            printerKey = segments.get(proxyIndex + 1);
            endpoint = segments.get(proxyIndex + 2);
        } else {
            endpoint = segments.get(proxyIndex + 1);
            if (proxyIndex > 0) {
                // prefix form: /<printer>/hw_proxy/<endpoint> (what Odoo produces)
                printerKey = segments.get(proxyIndex - 1);
            }
        }

        String fromQuery = queryParam(rawQuery, "printer");
        if (fromQuery != null && !fromQuery.isBlank()) {
            printerKey = fromQuery;
        }

        return new Route(endpoint.toLowerCase(java.util.Locale.ROOT), printerKey);
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * The printer this request must print on.
     *
     * @throws IllegalArgumentException if the route names a printer that isn't configured —
     *         falling back to the default printer here would silently print kitchen
     *         orders on the cashier's printer.
     * @throws IllegalStateException if no printer is configured at all.
     */
    public PrinterConfig resolve(HttpExchange exchange) {
        Route route = parse(exchange);
        String key = route == null ? null : route.printerKey();
        if (key != null && !key.isBlank()) {
            return config.getPrinterByRoute(key).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown printer route '" + key + "'. Configured routes: " + describeRoutes()));
        }
        return config.getDefaultPrinter()
                .orElseThrow(() -> new IllegalStateException("No printer configured"));
    }

    /** Like {@link #resolve}, but empty instead of throwing — for status/health endpoints. */
    public Optional<PrinterConfig> find(HttpExchange exchange) {
        Route route = parse(exchange);
        if (route != null && route.hasPrinterKey()) {
            return config.getPrinterByRoute(route.printerKey());
        }
        return config.getDefaultPrinter();
    }

    public AppConfig config() {
        return config;
    }

    private String describeRoutes() {
        if (config.printers.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (PrinterConfig p : config.printers) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.routeSlug());
        }
        return sb.toString();
    }
}
