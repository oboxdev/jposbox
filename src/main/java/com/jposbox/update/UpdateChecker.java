package com.jposbox.update;

import com.google.gson.Gson;
import com.jposbox.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks a remote JSON manifest for newer versions and can download the matching
 * installer for the current OS. Never installs silently — the downloaded
 * installer is opened with the OS's native handler (Finder/dmg, msiexec, etc.),
 * which still requires the user to click through it.
 */
public class UpdateChecker {

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());
    private static final Gson GSON = new Gson();
    private static final String GITHUB_REPO = "oboxdev/jposbox";

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
                    .followRedirects(HttpClient.Redirect.NORMAL)
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

    /** Installer file name published by the release workflow for the current OS. */
    public static String assetFileName(String version) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "jPosBox-" + version + ".dmg";
        }
        if (os.contains("win")) {
            return "jPosBox-" + version + ".msi";
        }
        return "jposbox_" + version + "-1_amd64.deb";
    }

    /** GitHub Releases download URL for the installer matching the current OS. */
    public static String assetDownloadUrl(String version) {
        return "https://github.com/" + GITHUB_REPO + "/releases/download/v" + version + "/" + assetFileName(version);
    }

    /** Downloads the installer to ~/.jposbox/updates/ and returns its path. */
    public static Path downloadInstaller(String url) throws IOException, InterruptedException {
        Path dir = AppConfig.homeDir().resolve("updates");
        Files.createDirectories(dir);
        Path dest = dir.resolve(url.substring(url.lastIndexOf('/') + 1));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("Download failed: HTTP " + resp.statusCode());
        }
        return dest;
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
