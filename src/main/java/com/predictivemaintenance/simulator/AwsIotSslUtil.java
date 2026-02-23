package com.predictivemaintenance.simulator;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Utility class to build an {@link SSLSocketFactory} for mutual TLS (mTLS) connections
 * to AWS IoT Core using X.509 certificates.
 * <p>
 * AWS IoT Core requires:
 * <ul>
 *   <li><b>AWS Root CA</b> – e.g. AmazonRootCA1.pem (trust store)</li>
 *   <li><b>Device Certificate</b> – e.g. certificate.pem.crt (client identity)</li>
 *   <li><b>Private Key</b> – e.g. private.pem.key (client authentication)</li>
 * </ul>
 * After downloading these from the AWS IoT Console (Create certificate → Activate →
 * Download), pass their file paths to {@link #createSocketFactory(String, String, String)}.
 * The returned SSLSocketFactory can be set on Paho's {@link org.eclipse.paho.client.mqttv3.MqttConnectOptions}
 * via {@code setSocketFactory(...)}.
 */
public final class AwsIotSslUtil {

    private static final String SSL_PROTOCOL = "TLSv1.2";
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String CERTIFICATE_TYPE = "X.509";

    private AwsIotSslUtil() {
        // Utility class; prevent instantiation.
    }

    /**
     * Creates an SSLSocketFactory suitable for connecting to AWS IoT Core using
     * the given PEM file paths. Use this factory with the Eclipse Paho MQTT client.
     *
     * @param rootCaPath    Path to the AWS Root CA PEM file (e.g. AmazonRootCA1.pem).
     * @param certPath     Path to the device certificate PEM file (e.g. certificate.pem.crt).
     * @param privateKeyPath Path to the device private key PEM file (e.g. private.pem.key).
     * @return SSLSocketFactory configured for mTLS with AWS IoT Core.
     * @throws Exception if files cannot be read or SSL context cannot be built.
     */
    public static SSLSocketFactory createSocketFactory(String rootCaPath, String certPath, String privateKeyPath)
            throws Exception {
        // Load the AWS Root CA into a trust store (server authentication).
        TrustManagerFactory tmf = createTrustManagerFactory(rootCaPath);

        // Load the device certificate and private key into a key store (client authentication).
        KeyManagerFactory kmf = createKeyManagerFactory(certPath, privateKeyPath);

        // Build TLS context and socket factory.
        SSLContext ctx = SSLContext.getInstance(SSL_PROTOCOL);
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx.getSocketFactory();
    }

    /**
     * Builds a TrustManagerFactory that trusts the AWS Root CA.
     * This is used to verify the AWS IoT Core server certificate during TLS handshake.
     */
    private static TrustManagerFactory createTrustManagerFactory(String rootCaPath) throws Exception {
        X509Certificate caCert = loadX509Certificate(rootCaPath);
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE);
        trustStore.load(null, null);
        trustStore.setCertificateEntry("aws-root-ca", caCert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    /**
     * Builds a KeyManagerFactory with the device certificate and private key.
     * AWS IoT uses this to authenticate the client (device) during mTLS.
     */
    private static KeyManagerFactory createKeyManagerFactory(String certPath, String privateKeyPath) throws Exception {
        X509Certificate clientCert = loadX509Certificate(certPath);
        PrivateKey privateKey = loadPrivateKey(privateKeyPath);

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, null);
        keyStore.setCertificateEntry("device-cert", clientCert);
        keyStore.setKeyEntry("device-key", privateKey, new char[0], new Certificate[]{clientCert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        return kmf;
    }

    /**
     * Loads a single X.509 certificate from a PEM file.
     * Handles both "-----BEGIN CERTIFICATE-----" PEM and raw DER (e.g. .crt) content.
     */
    private static X509Certificate loadX509Certificate(String path) throws Exception {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            throw new IOException("Certificate file not found: " + path);
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String content = new String(bytes);

        byte[] der;
        if (content.contains("-----BEGIN CERTIFICATE-----")) {
            der = decodePemCertificate(content);
        } else {
            // Assume raw Base64 (e.g. some .crt exports).
            der = java.util.Base64.getDecoder().decode(content.replaceAll("\\s", ""));
        }

        CertificateFactory cf = CertificateFactory.getInstance(CERTIFICATE_TYPE);
        Certificate cert = cf.generateCertificate(new java.io.ByteArrayInputStream(der));
        if (!(cert instanceof X509Certificate)) {
            throw new IllegalArgumentException("Expected X.509 certificate: " + path);
        }
        return (X509Certificate) cert;
    }

    /**
     * Extracts Base64-encoded certificate content from PEM and decodes it.
     */
    private static byte[] decodePemCertificate(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(base64);
    }

    /**
     * Loads a private key from a PEM file. Supports both PKCS#1 (RSA PRIVATE KEY)
     * and PKCS#8 (PRIVATE KEY) format, as typically provided by AWS IoT Console.
     */
    private static PrivateKey loadPrivateKey(String path) throws Exception {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            throw new IOException("Private key file not found: " + path);
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String content = new String(bytes);

        if (content.contains("-----BEGIN PRIVATE KEY-----") || content.contains("-----BEGIN PKCS8")) {
            return loadPkcs8PrivateKey(content);
        }
        if (content.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            return loadPkcs1PrivateKey(content);
        }
        throw new IllegalArgumentException(
                "Unsupported private key format. Expected PEM with BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY: " + path);
    }

    /**
     * Loads a PKCS#8 format private key (PEM: "-----BEGIN PRIVATE KEY-----").
     */
    private static PrivateKey loadPkcs8PrivateKey(String pem) throws Exception {
        byte[] der = decodePemKey(pem, "PRIVATE KEY");
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(der);
        return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /**
     * Loads a PKCS#1 format private key (PEM: "-----BEGIN RSA PRIVATE KEY-----")
     * by converting to PKCS#8 using Bouncy Castle's PEMParser (avoids extra dependency
     * for manual ASN.1 conversion). If Bouncy Castle is not used, we can use a simple
     * conversion: PKCS#1 is the raw RSA key; we can wrap it in PKCS#8 programmatically.
     *
     * Java's KeyFactory does not accept PKCS#1 directly. We use Bouncy Castle's
     * PEMParser to read the PEM and JcaPEMKeyConverter to get the PrivateKey.
     */
    private static PrivateKey loadPkcs1PrivateKey(String pem) throws Exception {
        try {
            return loadPkcs1WithBouncyCastle(pem);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "PKCS#1 (RSA PRIVATE KEY) detected. Add Bouncy Castle (bcpkix-jdk18on) to pom.xml, " +
                            "or convert the key to PKCS#8: openssl pkcs8 -topk8 -in private.pem.key -out private-pkcs8.pem -nocrypt",
                    e);
        }
    }

    /**
     * Uses Bouncy Castle to parse PEM and extract PrivateKey (supports PKCS#1 and PKCS#8).
     */
    private static PrivateKey loadPkcs1WithBouncyCastle(String pem) throws Exception {
        org.bouncycastle.openssl.PEMParser parser = new org.bouncycastle.openssl.PEMParser(
                new java.io.StringReader(pem));
        Object object = parser.readObject();
        parser.close();
        if (object == null) {
            throw new IllegalArgumentException("No PEM object found in private key content.");
        }
        org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter converter =
                new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter();
        if (object instanceof org.bouncycastle.openssl.PEMKeyPair) {
            return converter.getKeyPair((org.bouncycastle.openssl.PEMKeyPair) object).getPrivate();
        }
        if (object instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair) {
            // AWS IoT private keys are typically not password-protected; support encrypted if needed.
            throw new UnsupportedOperationException("Encrypted private key not supported. Use an unencrypted PEM.");
        }
        if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
            return converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) object);
        }
        throw new IllegalArgumentException("Unsupported PEM object type: " + object.getClass().getName());
    }

    private static byte[] decodePemKey(String pem, String keyLabel) {
        String begin = "-----BEGIN " + keyLabel + "-----";
        String end = "-----END " + keyLabel + "-----";
        if (!pem.contains(begin) || !pem.contains(end)) {
            throw new IllegalArgumentException("PEM content must contain " + begin + " and " + end);
        }
        String base64 = pem.substring(pem.indexOf(begin) + begin.length(), pem.indexOf(end))
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(base64);
    }
}
