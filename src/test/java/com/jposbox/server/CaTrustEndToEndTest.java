package com.jposbox.server;

import com.jposbox.config.AppConfig;
import com.jposbox.printer.PrinterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a user's browser does after installing the CA: download {@code /ca.crt}
 * over plain HTTP, trust only that, then reach the HTTPS API with a normal
 * (hostname-verifying) handshake.
 */
class CaTrustEndToEndTest {

    private final String originalHome = System.getProperty("user.home");
    private ApiServer server;

    @AfterEach
    void cleanUp() {
        if (server != null) {
            server.stop();
        }
        System.setProperty("user.home", originalHome);
    }

    @Test
    void clientTrustingOnlyTheDownloadedCaReachesHttpsWithoutWarnings(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        AppConfig config = new AppConfig();
        config.httpPort = freePort();
        config.httpsPort = freePort();
        config.httpsEnabled = true;
        server = new ApiServer(config, new PrinterManager());
        server.start();

        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + config.httpPort + "/ca.crt")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals("application/x-x509-ca-cert", download.headers().firstValue("Content-Type").orElse(""));

        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("jposbox-ca", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(download.body())));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext trusting = SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);

        // Default JDK client verifies the hostname against the SAN, like a browser does.
        HttpClient https = HttpClient.newBuilder().sslContext(trusting).build();
        for (String host : new String[]{"127.0.0.1", "localhost"}) {
            HttpResponse<String> hello = https.send(
                    HttpRequest.newBuilder(URI.create("https://" + host + ":" + config.httpsPort + "/hw_proxy/hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, hello.statusCode(), host);
            assertEquals("ok", hello.body());
        }

        // Without the CA installed the very same request is refused.
        Exception e = assertThrows(Exception.class, () -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + config.httpsPort + "/hw_proxy/hello")).build(),
                HttpResponse.BodyHandlers.ofString()));
        assertTrue(e instanceof SSLHandshakeException || e.getCause() instanceof SSLHandshakeException, e.toString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
