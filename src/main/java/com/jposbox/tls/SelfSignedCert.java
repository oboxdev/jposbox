package com.jposbox.tls;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the TLS material used to serve the API over HTTPS to browsers/POS
 * clients on the same LAN: a local certificate authority (CA) plus a server
 * certificate signed by it.
 *
 * <p>Why two certificates instead of one self-signed cert: the server
 * certificate has to change whenever this machine's IP changes (a DHCP lease,
 * a new network), and a self-signed cert that does so asks every browser to
 * trust it all over again. With a CA, the user installs {@code ca.crt} as a
 * trusted root once (see {@link CaTrustInstaller}, or the {@code /ca.crt}
 * download for other devices) and every later server certificate it signs is
 * trusted automatically.
 *
 * <p>A trusted CA whose key leaks could be used to impersonate any site, so it
 * is locked down with a critical Name Constraints extension: it can only vouch
 * for {@code localhost}, {@code *.local} and private/loopback IPv4 ranges.
 * Browsers and the JDK both enforce this, so the CA is useless against
 * addresses outside that scope even if its key is stolen.
 *
 * <p>The server certificate's Subject Alternative Names must include whatever
 * address the browser actually types in the URL bar (Chrome and other modern
 * browsers ignore the Subject CN entirely and validate SAN only). It's not
 * enough to cover just {@code InetAddress.getLocalHost()}: on a machine with
 * several network interfaces (Wi-Fi + Ethernet + a VPN adapter, common on
 * laptops) that call often returns the wrong one, or a loopback address, with
 * no relation to the LAN IP other devices actually use to reach this machine.
 * Every non-loopback IPv4 address inside the CA's permitted ranges, across
 * every interface, is included instead.
 */
public class SelfSignedCert {

    private static final Logger LOG = Logger.getLogger(SelfSignedCert.class.getName());
    private static final String ALIAS = "jposbox";
    private static final String CA_ALIAS = "jposbox-ca";
    private static final char[] PASSWORD = "jposbox".toCharArray();

    // Apple rejects server certs valid for more than 825 days when they chain
    // to a user-installed root; stay under that.
    private static final long LEAF_VALIDITY_DAYS = 800;
    private static final long CA_VALIDITY_DAYS = 3650;
    private static final long RENEW_BEFORE_DAYS = 30;

    /** {a, b, c, d, prefixLength}: the only IPv4 ranges the CA may issue for. */
    private static final int[][] PERMITTED_IPV4 = {
            {10, 0, 0, 0, 8},
            {172, 16, 0, 0, 12},
            {192, 168, 0, 0, 16},
            {169, 254, 0, 0, 16},
            {100, 64, 0, 0, 10}, // CGNAT, also used by Tailscale
            {127, 0, 0, 0, 8},
    };
    private static final String[] PERMITTED_DNS = {"localhost", "local"};

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static Path keystorePath() {
        return com.jposbox.config.AppConfig.homeDir().resolve("keystore.p12");
    }

    /** The CA's private key + certificate. Never leaves this machine. */
    public static Path caKeystorePath() {
        return com.jposbox.config.AppConfig.homeDir().resolve("ca.p12");
    }

    /** The CA's public certificate (PEM): what gets installed as trusted / downloaded by other devices. */
    public static Path caCertPath() {
        return com.jposbox.config.AppConfig.homeDir().resolve("ca.crt");
    }

    /**
     * Returns the server keystore, generating the CA and/or a new server
     * certificate when needed: none exists yet, this machine's network
     * addresses no longer match the ones the existing cert was issued for
     * (e.g. a DHCP lease changed), it is about to expire, or it was not issued
     * by the current CA (a self-signed keystore from before the CA existed).
     * Only the server certificate is replaced in those cases; the CA, and so
     * any trust the user already granted it, is kept.
     */
    public static synchronized KeyStore loadOrCreate() throws Exception {
        KeyStore caStore = loadOrCreateCa();
        X509Certificate caCert = (X509Certificate) caStore.getCertificate(CA_ALIAS);
        PrivateKey caKey = (PrivateKey) caStore.getKey(CA_ALIAS, PASSWORD);

        Path path = keystorePath();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                ks.load(in, PASSWORD);
            }
            if (leafIsCurrent(ks, caCert)) {
                return ks;
            }
            LOG.info("The HTTPS server certificate is missing, expiring, not issued by the jPosBox CA, or "
                    + "this machine's network addresses changed since it was generated; regenerating it.");
        } else {
            ks.load(null, null);
        }
        generateLeaf(ks, caCert, caKey);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        restrictToOwner(path);
        return ks;
    }

    public static char[] password() {
        return PASSWORD;
    }

    /** Makes sure the CA exists on disk and returns the path of its public certificate (PEM). */
    public static synchronized Path ensureCaCertFile() throws Exception {
        loadOrCreateCa();
        return caCertPath();
    }

    /** SHA-256 fingerprint of the CA certificate, colon-separated hex, for out-of-band verification. */
    public static synchronized String caFingerprintSha256() throws Exception {
        X509Certificate caCert = (X509Certificate) loadOrCreateCa().getCertificate(CA_ALIAS);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(caCert.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    /**
     * Every non-loopback IPv4 address this machine currently has, on any
     * interface, that the CA is allowed to issue for (private/link-local/CGNAT
     * ranges). Public addresses are left out: a certificate naming one would
     * violate the CA's name constraints and be rejected by browsers outright.
     */
    public static List<String> currentIPv4Addresses() {
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
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && isPermittedAddress(addr)) {
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

    private static boolean isPermittedAddress(InetAddress addr) {
        byte[] b = addr.getAddress();
        for (int[] range : PERMITTED_IPV4) {
            int prefix = range[4];
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            if ((toInt(b) & mask) == (toInt(range[0], range[1], range[2], range[3]) & mask)) {
                return true;
            }
        }
        return false;
    }

    private static int toInt(byte[] b) {
        return toInt(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff, b[3] & 0xff);
    }

    private static int toInt(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    // --- CA ----------------------------------------------------------------

    private static KeyStore loadOrCreateCa() throws Exception {
        Path path = caKeystorePath();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                ks.load(in, PASSWORD);
            }
            if (!Files.exists(caCertPath())) {
                writePem(caCertPath(), (X509Certificate) ks.getCertificate(CA_ALIAS));
            }
            return ks;
        }

        ks.load(null, null);
        generateCa(ks);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        restrictToOwner(path);
        writePem(caCertPath(), (X509Certificate) ks.getCertificate(CA_ALIAS));
        return ks;
    }

    private static void generateCa(KeyStore ks) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(3072);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=jPosBox Local CA, O=jPosBox");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, newSerial(), notBefore(), notAfter(CA_VALIDITY_DAYS), subject, keyPair.getPublic());

        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.nameConstraints, true, nameConstraints());
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(keyPair.getPublic()));

        X509Certificate cert = sign(builder, keyPair.getPrivate());
        ks.setKeyEntry(CA_ALIAS, keyPair.getPrivate(), PASSWORD, new java.security.cert.Certificate[]{cert});
        LOG.info("Generated jPosBox local CA (SHA-256 " + HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())) + ")");
    }

    private static NameConstraints nameConstraints() {
        List<GeneralSubtree> permitted = new ArrayList<>();
        for (String dns : PERMITTED_DNS) {
            permitted.add(new GeneralSubtree(new GeneralName(GeneralName.dNSName, dns)));
        }
        for (int[] range : PERMITTED_IPV4) {
            int prefix = range[4];
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            byte[] ipAndMask = {
                    (byte) range[0], (byte) range[1], (byte) range[2], (byte) range[3],
                    (byte) (mask >>> 24), (byte) (mask >>> 16), (byte) (mask >>> 8), (byte) mask};
            permitted.add(new GeneralSubtree(new GeneralName(GeneralName.iPAddress, new DEROctetString(ipAndMask))));
        }
        return new NameConstraints(permitted.toArray(new GeneralSubtree[0]), null);
    }

    // --- server certificate --------------------------------------------------

    /** True only if the stored cert chains to this CA, isn't near expiry, and covers every current IPv4 address. */
    private static boolean leafIsCurrent(KeyStore ks, X509Certificate caCert) {
        try {
            X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
            if (cert == null) {
                return false;
            }
            try {
                cert.verify(caCert.getPublicKey()); // fails for a legacy self-signed cert
            } catch (GeneralSecurityException e) {
                return false;
            }
            long renewAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(RENEW_BEFORE_DAYS);
            if (cert.getNotAfter().getTime() < renewAt) {
                return false;
            }
            return coversCurrentAddresses(cert);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not inspect existing HTTPS certificate, regenerating it", e);
            return false;
        }
    }

    private static boolean coversCurrentAddresses(X509Certificate cert) throws Exception {
        List<String> currentIps = currentIPv4Addresses();
        if (currentIps.isEmpty()) {
            return true; // nothing to check against (e.g. offline); don't churn the cert
        }
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return false;
        }
        Set<String> coveredIps = new LinkedHashSet<>();
        for (List<?> san : sans) {
            if (san.size() >= 2 && Integer.valueOf(GeneralName.iPAddress).equals(san.get(0))) {
                coveredIps.add(String.valueOf(san.get(1)));
            }
        }
        return coveredIps.containsAll(currentIps);
    }

    private static void generateLeaf(KeyStore ks, X509Certificate caCert, PrivateKey caKey) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        X500Name subject = new X500Name("CN=jPosBox, O=jPosBox");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, newSerial(), notBefore(), notAfter(LEAF_VALIDITY_DAYS), subject, keyPair.getPublic());

        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        GeneralNames sans = subjectAltNames();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.subjectAlternativeName, false, sans);
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(caCert));

        X509Certificate cert = sign(builder, caKey);
        ks.setKeyEntry(ALIAS, keyPair.getPrivate(), PASSWORD, new java.security.cert.Certificate[]{cert, caCert});

        LOG.info("Generated HTTPS certificate covering: " + describeSans(sans));
    }

    private static GeneralNames subjectAltNames() {
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        try {
            // Only *.local fits the CA's name constraints; any other hostname would invalidate the chain.
            String host = InetAddress.getLocalHost().getHostName();
            if (host.toLowerCase().endsWith(".local")) {
                names.add(new GeneralName(GeneralName.dNSName, host));
            }
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

    // --- helpers -----------------------------------------------------------

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey signingKey) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signingKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static BigInteger newSerial() {
        return new BigInteger(127, new java.security.SecureRandom()).add(BigInteger.ONE);
    }

    private static Date notBefore() {
        return new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
    }

    private static Date notAfter(long days) {
        return new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days));
    }

    private static void writePem(Path path, X509Certificate cert) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded());
        Files.writeString(path, "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n",
                StandardCharsets.US_ASCII);
    }

    /** Best effort: the CA key must not be readable by other local users. No-op where POSIX perms don't exist. */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
