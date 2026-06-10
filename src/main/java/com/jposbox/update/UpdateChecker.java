package com.jposbox.update;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Checks a remote JSON manifest for newer versions. Never auto-installs, only reports. */
public class UpdateChecker {

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());
    private static final Gson GSON = new Gson();

    /** Returns the version baked into the jar manifest by jpackage/shadowJar, or "dev" outside a jar. */
    public static String getCurrentVersion() {
        String v = UpdateChecker.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    /** Fetches updateUrl and returns the parsed manifest if it describes a newer version than current. */
    public static Optional<UpdateInfo> checkForUpdate(String updateUrl, String currentVersion) {
        if (updateUrl == null || updateUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(updateUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("Update check failed: HTTP " + resp.statusCode());
                return Optional.empty();
            }
            UpdateInfo info = GSON.fromJson(resp.body(), UpdateInfo.class);
            if (info == null || info.version == null) {
                return Optional.empty();
            }
            if (compareVersions(info.version, currentVersion) > 0) {
                return Optional.of(info);
            }
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "Update check failed", e);
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Update check failed: bad response", e);
            return Optional.empty();
        }
    }

    /** Compares semantic versions like "1.2.3". Returns &gt;0 if a &gt; b, &lt;0 if a &lt; b, 0 if equal. */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = parsePart(pa, i);
            int nb = parsePart(pb, i);
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static int parsePart(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
