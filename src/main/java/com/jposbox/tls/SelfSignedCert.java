package com.jposbox.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates (or loads) a self-signed certificate + PKCS12 keystore used to
 * serve the API over HTTPS for browsers/POS clients on the same LAN.
 *
 * <p>The certificate's Subject Alternative Names must include whatever
 * address the browser actually types in the URL bar (Chrome and other modern
 * browsers ignore the Subject CN entirely and validate SAN only). It's not
 * enough to cover just {@code InetAddress.getLocalHost()}: on a machine with
 * several network interfaces (Wi-Fi + Ethernet + a VPN adapter, common on
 * laptops) that call often returns the wrong one, or a loopback address, with
 * no relation to the LAN IP other devices actually use to reach this machine.
 * Every non-loopback IPv4 address across every interface is included instead.
 */
public class SelfSignedCert {

    private static final Logger LOG = Logger.getLogger(SelfSignedCert.class.getName());
    private static final String ALIAS = "jposbox";
    private static final char[] PASSWORD = "jposbox".toCharArray();
    private static final int SAN_TYPE_DNS = 2;
    private static final int SAN_TYPE_IP = 7;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static Path keystorePath() {
        return com.jposbox.config.AppConfig.homeDir().resolve("keystore.p12");
    }

    /**
     * Returns the keystore, generating a new self-signed cert if none exists
     * yet, or if this machine's network addresses no longer match the ones
     * the existing cert was issued for (e.g. a DHCP lease changed since it was
     * first generated) — otherwise that mismatch would silently keep breaking
     * HTTPS for anyone but whoever happened to be on the original address.
     */
    public static KeyStore loadOrCreate() throws Exception {
        Path path = keystorePath();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                ks.load(in, PASSWORD);
            }
            if (certCoversCurrentAddresses(ks)) {
                return ks;
            }
            LOG.info("This machine's network addresses changed since the HTTPS certificate was "
                    + "generated (or none of them were covered yet); regenerating it.");
        } else {
            ks.load(null, null);
        }
        generate(ks);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        return ks;
    }

    public static char[] password() {
        return PASSWORD;
    }

    /** Every non-loopback IPv4 address this machine currently has, on any interface. */
    static List<String> currentIPv4Addresses() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!isUsable(iface)) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            LOG.log(Level.WARNING, "Could not enumerate network interfaces for the HTTPS certificate", e);
        }
        return List.copyOf(ips);
    }

    private static boolean isUsable(NetworkInterface iface) {
        try {
            return iface.isUp() && !iface.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }

    /** True only if every current IPv4 address is already present in the stored cert's SAN. */
    private static boolean certCoversCurrentAddresses(KeyStore ks) {
        List<String> currentIps = currentIPv4Addresses();
        if (currentIps.isEmpty()) {
            return true; // nothing to check against (e.g. offline); don't churn the cert
        }
        try {
            X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
            if (cert == null) {
                return false;
            }
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return false;
            }
            Set<String> coveredIps = new LinkedHashSet<>();
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(SAN_TYPE_IP).equals(san.get(0))) {
                    coveredIps.add(String.valueOf(san.get(1)));
                }
            }
            return coveredIps.containsAll(currentIps);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not inspect existing HTTPS certificate, regenerating it", e);
            return false;
        }
    }

    private static void generate(KeyStore ks) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=jPosBox, O=jPosBox");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650));

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        GeneralNames sans = subjectAltNames();
        certBuilder.addExtension(Extension.subjectAlternativeName, false, sans);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

        PrivateKey privateKey = keyPair.getPrivate();
        ks.setKeyEntry(ALIAS, privateKey, PASSWORD, new java.security.cert.Certificate[]{cert});

        LOG.info("Generated HTTPS certificate covering: " + describeSans(sans));
    }

    private static GeneralNames subjectAltNames() {
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        try {
            names.add(new GeneralName(GeneralName.dNSName, InetAddress.getLocalHost().getHostName()));
        } catch (IOException ignored) {
            // best-effort only; the IP addresses below are what actually matters
        }
        for (String ip : currentIPv4Addresses()) {
            names.add(new GeneralName(GeneralName.iPAddress, ip));
        }
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    private static String describeSans(GeneralNames sans) {
        List<String> parts = new ArrayList<>();
        for (GeneralName name : sans.getNames()) {
            parts.add(name.getName().toString());
        }
        return String.join(", ", parts);
    }
}
