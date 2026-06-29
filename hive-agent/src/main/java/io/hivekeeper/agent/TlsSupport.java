package io.hivekeeper.agent;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Enumeration;

/** Builds the agent's mTLS context from its client keystore (key + cert) and the CA truststore. */
final class TlsSupport {

    private TlsSupport() {
    }

    /**
     * Extracts the agent's private key from its PKCS12 keystore — the same key material mTLS uses — so the
     * agent can unseal credentials the gateway sealed to the matching public key (see
     * {@link KeystoreSecretUnsealer}). Returns the first private-key entry found.
     */
    static PrivateKey privateKey(String keystorePath, char[] password) throws Exception {
        KeyStore keyStore = load(keystorePath, password);
        for (Enumeration<String> aliases = keyStore.aliases(); aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                Key key = keyStore.getKey(alias, password);
                if (key instanceof PrivateKey privateKey) {
                    return privateKey;
                }
            }
        }
        throw new IllegalStateException("no private key entry found in keystore " + keystorePath);
    }

    static SSLContext fromKeystores(String keystorePath, char[] keystorePassword,
                                    String truststorePath, char[] truststorePassword) throws Exception {
        KeyStore keyStore = load(keystorePath, keystorePassword);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword);

        KeyStore trustStore = load(truststorePath, truststorePassword);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static KeyStore load(String path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password);
        }
        return store;
    }
}
