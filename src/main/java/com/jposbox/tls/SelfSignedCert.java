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
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates (or loads) a self-signed certificate + PKCS12 keystore used to
 * serve the API over HTTPS for browsers/POS clients on the same LAN.
 */
public class SelfSignedCert {

    private static final String ALIAS = "jposbox";
    private static final char[] PASSWORD = "jposbox".toCharArray();

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static Path keystorePath() {
        return com.jposbox.config.AppConfig.homeDir().resolve("keystore.p12");
    }

    /** Returns the keystore, generating a new self-signed cert if needed. */
    public static KeyStore loadOrCreate() throws Exception {
        Path path = keystorePath();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                ks.load(in, PASSWORD);
                return ks;
            }
        }
        ks.load(null, null);
        generate(ks);
        try (OutputStream out = Files.newOutputStream(path)) {
            ks.store(out, PASSWORD);
        }
        return ks;
    }

    public static char[] password() {
        return PASSWORD;
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
        certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

        PrivateKey privateKey = keyPair.getPrivate();
        ks.setKeyEntry(ALIAS, privateKey, PASSWORD, new java.security.cert.Certificate[]{cert});
    }

    private static GeneralNames subjectAltNames() {
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        try {
            InetAddress local = InetAddress.getLocalHost();
            names.add(new GeneralName(GeneralName.dNSName, local.getHostName()));
            names.add(new GeneralName(GeneralName.iPAddress, local.getHostAddress()));
        } catch (IOException ignored) {
        }
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }
}
