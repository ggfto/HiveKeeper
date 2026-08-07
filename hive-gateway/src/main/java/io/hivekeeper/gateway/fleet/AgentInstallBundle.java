package io.hivekeeper.gateway.fleet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the agent's install bundle: the zip an operator downloads from the console and unpacks on the on-prem
 * machine to get a running agent with two commands.
 *
 * <p>It exists because enrollment used to end with a wall of text to transcribe — copy the token, copy three
 * URLs, save the CA out of a container log, invent a vault key, find the right compose file. Every one of those
 * is a fact the gateway already holds or can generate, and every one of them is a chance to paste the wrong
 * thing into a file whose failure mode is a silent, permanently-offline agent.
 *
 * <p>The compose and the {@code .env} are <b>not</b> written here: they are the real
 * {@code deploy/portainer/agent-compose.yml} and {@code agent.env.example}, copied into the gateway's resources
 * at build time and filled in. That is deliberate. A hand-written copy of the compose inside the gateway would
 * be a second source of truth that drifts from the one the docs describe, and it would drift invisibly — the
 * bundle only fails on someone else's machine. Filling the template instead means a new setting added to the
 * example shows up in the bundle for free, still carrying its explanatory comment.
 *
 * <p>Pure and deterministic (fixed entry timestamps, secrets passed in rather than generated), so the whole
 * thing is unit-testable: {@link #zip} of the same inputs is the same bytes.
 */
public final class AgentInstallBundle {

    /** Entry keys we fill in the env template. Anything else in the template keeps its example value. */
    private static final String KEY_TAG = "HIVEKEEPER_TAG";
    private static final String KEY_DOMAIN = "HIVEKEEPER_AGENT_DOMAIN";
    private static final String KEY_AGENT_ID = "HIVEKEEPER_AGENT_ID";
    private static final String KEY_TOKEN = "HIVEKEEPER_ENROLLMENT_TOKEN";
    private static final String KEY_STORE_PASSWORD = "HIVEKEEPER_AGENT_STORE_PASSWORD";
    private static final String KEY_VAULT_KEY = "HIVEKEEPER_VAULT_KEY";

    /** A line that assigns an env key: {@code KEY=...}. A commented-out line is left alone on purpose — it is
     *  an opt-in the operator has to make deliberately (CONTAINER_SOCK for podman, for one). */
    private static final Pattern ASSIGNMENT = Pattern.compile("^([A-Z][A-Z0-9_]*)=.*$");

    /** Fixed so the same inputs produce byte-identical zips; the mtime of a generated file carries no meaning. */
    private static final long ENTRY_TIME = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    private AgentInstallBundle() {
    }

    /**
     * Everything that varies per bundle. {@code storePassword} and {@code vaultKey} are freshly generated
     * secrets for THIS agent — the vault key in particular encrypts the credential vault at rest, and losing it
     * loses the vault, which is why the README says to back it up.
     */
    public record Inputs(String agentId, String token, String domain, String caPem, String tag,
                         String storePassword, String vaultKey) {
    }

    /**
     * Assemble the zip. {@code composeTemplate} and {@code envTemplate} are the build-time copies of the real
     * deploy files; {@code caPem} may be null when the gateway has no CA, in which case the bundle carries no
     * ca.pem and the README says where to get one.
     */
    public static byte[] zip(Inputs in, String composeTemplate, String envTemplate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            write(zip, "docker-compose.yml", composeTemplate);
            write(zip, ".env", envFile(in, envTemplate));
            if (in.caPem() != null && !in.caPem().isBlank()) {
                write(zip, "ca.pem", in.caPem().endsWith("\n") ? in.caPem() : in.caPem() + "\n");
            }
            write(zip, "README.txt", readme(in));
        }
        return out.toByteArray();
    }

    /**
     * Fill the env template line by line, replacing only the keys we know and leaving every comment intact.
     * A key we have no value for keeps the example's — which is how {@code CF_ACCESS_CLIENT_ID} stays blank:
     * it is a Cloudflare Zero Trust service token, issued in Cloudflare's dashboard, and the gateway has no way
     * to know it. The README calls that out as the one thing left to fill.
     */
    static String envFile(Inputs in, String template) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_TAG, in.tag());
        values.put(KEY_DOMAIN, in.domain());
        values.put(KEY_AGENT_ID, in.agentId());
        values.put(KEY_TOKEN, in.token());
        values.put(KEY_STORE_PASSWORD, in.storePassword());
        values.put(KEY_VAULT_KEY, in.vaultKey());
        values.values().removeIf(v -> v == null);

        StringBuilder filled = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            Matcher m = ASSIGNMENT.matcher(line.stripTrailing());
            if (m.matches() && values.containsKey(m.group(1))) {
                filled.append(m.group(1)).append('=').append(values.get(m.group(1)));
            } else {
                filled.append(line.stripTrailing());
            }
            filled.append('\n');
        }
        // split("\n", -1) yields a trailing empty element for a template ending in a newline; the loop turned it
        // into one extra blank line. Collapse the tail back to a single terminating newline.
        while (filled.length() >= 2 && filled.charAt(filled.length() - 2) == '\n') {
            filled.setLength(filled.length() - 1);
        }
        return filled.toString();
    }

    static String readme(Inputs in) {
        StringBuilder r = new StringBuilder();
        r.append("HiveKeeper agent — ").append(in.agentId()).append("\n");
        r.append("=".repeat(30)).append("\n\n");
        r.append("Unpack this on a machine INSIDE the network your access points are on — not on the server\n");
        r.append("running the gateway. The agent reaches the APs over SSH on the LAN and dials OUT to the\n");
        r.append("gateway, so this machine needs no inbound port and no public address.\n\n");

        r.append("1. Open .env and fill in the two Cloudflare Access values:\n");
        r.append("     CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET\n");
        r.append("   They are a Zero Trust service token (Access > Applications > your agent app > a policy\n");
        r.append("   with Action = Service Auth). Without one, cloudflared tries to open a browser to log in,\n");
        r.append("   which never completes on a headless machine and leaves the agent permanently offline.\n");
        r.append("   Everything else in .env is already filled in for this agent.\n\n");
        r.append("2. docker compose up -d\n\n");
        r.append("3. docker compose logs -f agent\n");
        r.append("   Look for \"enrollment complete\". The agent generates its keypair locally, trades the\n");
        r.append("   one-time token below for a certificate, and appears as online in the console.\n\n");

        r.append("Agent id:        ").append(in.agentId()).append("\n");
        r.append("Gateway:         wss://").append(in.domain()).append(":9443/agent\n");
        r.append("Enrollment URL:  https://").append(in.domain()).append(":9443\n");
        r.append("Image tag:       ").append(in.tag()).append("  (matches the gateway — keep them in step)\n\n");

        if (in.caPem() == null || in.caPem().isBlank()) {
            r.append("!! No ca.pem in this bundle: this gateway has no CA configured. The agent cannot verify\n");
            r.append("   the gateway's certificate while enrolling until you supply one. Copy it from the\n");
            r.append("   pki-init container's log and save it next to docker-compose.yml as ca.pem.\n\n");
        }

        r.append("BACK UP, somewhere other than this machine:\n");
        r.append("  * HIVEKEEPER_VAULT_KEY in .env — it encrypts the device credential vault at rest.\n");
        r.append("    Lose it and the vault is unreadable ciphertext.\n");
        r.append("  * the agent's data volume — its certificate/identity, the vault, the pinned SSH host\n");
        r.append("    keys, and the git history of every device config, which is your rollback path.\n\n");
        r.append("The enrollment token is one-time: it is spent on first start, after which the agent renews\n");
        r.append("its own certificate over mTLS and never needs a token again. If this bundle is never used,\n");
        r.append("delete the agent in the console rather than leaving an unspent token lying around.\n");
        return r.toString();
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Read one of the build-time template copies out of the gateway's own jar. */
    public static String resource(String name) throws IOException {
        try (InputStream in = AgentInstallBundle.class.getResourceAsStream("/agent-install/" + name)) {
            if (in == null) {
                throw new IOException("missing packaged resource /agent-install/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
