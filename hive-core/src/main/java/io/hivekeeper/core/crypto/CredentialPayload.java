package io.hivekeeper.core.crypto;

import io.hivekeeper.core.spi.Credentials;
import java.nio.charset.StandardCharsets;

/**
 * The wire shape of the secret carried inside an {@link EnvelopeCipher} envelope: the username and password
 * on two lines ({@code username + "\n" + password}). Deliberately not JSON — no escaping or parser to get
 * wrong, and HiveOS credentials never contain a newline. The gateway {@link #encode}s and seals; the agent
 * unseals and {@link #decode}s. Keeping the format here keeps the two sides from drifting.
 */
public final class CredentialPayload {

    private CredentialPayload() {
    }

    public static byte[] encode(String username, String password) {
        return (username + "\n" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8);
    }

    public static Credentials decode(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        int nl = s.indexOf('\n');
        if (nl < 0) {
            throw new IllegalArgumentException("malformed credential payload (no username/password separator)");
        }
        return new Credentials(s.substring(0, nl), s.substring(nl + 1));
    }
}
