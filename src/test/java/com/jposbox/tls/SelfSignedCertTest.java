package com.jposbox.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HTTPS certificate actually covers addresses a browser can
 * reach this machine on -- not just localhost -- since Chrome/modern
 * browsers validate the Subject Alternative Name only, ignoring the CN.
 */
class SelfSignedCertTest {

    private final String originalHome = System.getProperty("user.home");

    /** Points AppConfig.homeDir() (and so the keystore path) at an isolated temp dir. */
    private void useTempHome(Path dir) {
        System.setProperty("user.home", dir.toString());
    }

    @AfterEach
    void restoreRealHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void currentIPv4AddressesExcludesLoopback() {
        for (String ip : SelfSignedCert.currentIPv4Addresses()) {
            assertFalse(ip.startsWith("127."), "loopback address leaked into the list: " + ip);
        }
    }

    @Test
    void generatedCertCoversLocalhostAnd127001(@TempDir Path dir) throws Exception {
        useTempHome(dir);
        KeyStore ks = SelfSignedCert.loadOrCreate();
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias());
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();

        assertTrue(containsDns(sans, "localhost"), sans.toString());
        assertTrue(containsIp(sans, "127.0.0.1"), sans.toString());
    }

    @Test
    void generatedCertCoversEveryCurrentMachineAddress(@TempDir Path dir) throws Exception {
        useTempHome(dir);
        KeyStore ks = SelfSignedCert.loadOrCreate();
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias());
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();

        for (String ip : SelfSignedCert.currentIPv4Addresses()) {
            assertTrue(containsIp(sans, ip), "cert SAN missing machine address " + ip + ": " + sans);
        }
    }

    @Test
    void certIsReusedAcrossCallsWhenStillValid(@TempDir Path dir) throws Exception {
        useTempHome(dir);
        KeyStore first = SelfSignedCert.loadOrCreate();
        X509Certificate firstCert = (X509Certificate) first.getCertificate(alias());

        KeyStore second = SelfSignedCert.loadOrCreate();
        X509Certificate secondCert = (X509Certificate) second.getCertificate(alias());

        assertEquals(firstCert.getSerialNumber(), secondCert.getSerialNumber(),
                "a cert that already covers every current address should not be regenerated");
    }

    @Test
    void staleCertMissingCurrentAddressIsRegenerated(@TempDir Path dir) throws Exception {
        useTempHome(dir);
        // Skip if this machine genuinely has no routable IPv4 address (e.g. fully offline CI) --
        // there would be nothing for a "stale" cert to be missing.
        if (SelfSignedCert.currentIPv4Addresses().isEmpty()) {
            return;
        }

        writeCertCoveringOnly(dir, "10.99.99.99"); // deliberately wrong/stale

        KeyStore reloaded = SelfSignedCert.loadOrCreate();
        X509Certificate fixed = (X509Certificate) reloaded.getCertificate(alias());
        Collection<List<?>> sans = fixed.getSubjectAlternativeNames();

        for (String ip : SelfSignedCert.currentIPv4Addresses()) {
            assertTrue(containsIp(sans, ip), "regenerated cert should cover " + ip + ": " + sans);
        }
        assertFalse(containsIp(sans, "10.99.99.99"), "stale address should be gone after regeneration");
    }

    // --- helpers -----------------------------------------------------------

    private String alias() throws Exception {
        Field f = SelfSignedCert.class.getDeclaredField("ALIAS");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private boolean containsIp(Collection<List<?>> sans, String ip) {
        return sans.stream().anyMatch(san -> Integer.valueOf(7).equals(san.get(0)) && ip.equals(san.get(1)));
    }

    private boolean containsDns(Collection<List<?>> sans, String name) {
        return sans.stream().anyMatch(san -> Integer.valueOf(2).equals(san.get(0)) && name.equals(san.get(1)));
    }

    /** Writes a minimal self-signed cert whose only SAN is the given IP, to simulate a stale keystore. */
    private void writeCertCoveringOnly(Path homeDir, String ip) throws Exception {
        org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name("CN=jPosBox, O=jPosBox");
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();

        org.bouncycastle.asn1.x509.GeneralNames sans = new org.bouncycastle.asn1.x509.GeneralNames(
                new org.bouncycastle.asn1.x509.GeneralName[]{
                        new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, ip)
                });
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                subject, java.math.BigInteger.ONE,
                new java.util.Date(System.currentTimeMillis() - 1000),
                new java.util.Date(System.currentTimeMillis() + 100_000_000L),
                subject, kp.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, sans);

        var signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        var holder = builder.build(signer);
        X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(holder);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias(), kp.getPrivate(), SelfSignedCert.password(),
                new java.security.cert.Certificate[]{cert});
        java.nio.file.Files.createDirectories(SelfSignedCert.keystorePath().getParent());
        try (var out = java.nio.file.Files.newOutputStream(SelfSignedCert.keystorePath())) {
            ks.store(out, SelfSignedCert.password());
        }
    }
}
