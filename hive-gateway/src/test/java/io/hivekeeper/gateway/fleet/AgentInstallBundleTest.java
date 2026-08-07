package io.hivekeeper.gateway.fleet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install bundle is the whole point of the download button: whatever it writes is what the operator runs,
 * unread. So these pin the things that would fail silently on someone else's machine — a token that did not get
 * substituted, a comment stripped out of the env file, a placeholder vault key shipping to production.
 */
class AgentInstallBundleTest {

    /** A miniature of deploy/portainer/agent.env.example: comments, blanks, a stale tag, a commented-out opt-in. */
    private static final String ENV_TEMPLATE = """
            # HiveKeeper agent — copy to `.env` next to agent-compose.yml and fill in.
            HIVEKEEPER_REGISTRY=ghcr.io/ggfto
            # Pin the SAME version as the gateway.
            HIVEKEEPER_TAG=0.9.0

            HIVEKEEPER_AGENT_DOMAIN=agents.example.org
            CF_ACCESS_CLIENT_ID=
            CF_ACCESS_CLIENT_SECRET=
            HIVEKEEPER_AGENT_ID=site-a-agent
            HIVEKEEPER_ENROLLMENT_TOKEN=
            HIVEKEEPER_AGENT_STORE_PASSWORD=
            HIVEKEEPER_VAULT_KEY=
            HIVEKEEPER_DEFAULT_USER=admin
            # CONTAINER_SOCK=/run/user/1000/podman/podman.sock
            """;

    private static final String COMPOSE = "name: hivekeeper-agent\nservices:\n  agent:\n";

    private static AgentInstallBundle.Inputs inputs() {
        return new AgentInstallBundle.Inputs("lab-agent", "enroll-abc123", "agents.gf2.in",
                "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----", "0.16.1",
                "d41d8cd98f00b204e9800998", "c2VjcmV0LWtleS1oZXJlLXBhZGRlZC10by0zMg==");
    }

    private static Map<String, String> entries(byte[] zip) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void fillsTheAgentsOwnValuesIntoTheEnvTemplate() {
        String env = AgentInstallBundle.envFile(inputs(), ENV_TEMPLATE);

        assertTrue(env.contains("HIVEKEEPER_AGENT_ID=lab-agent"));
        assertTrue(env.contains("HIVEKEEPER_ENROLLMENT_TOKEN=enroll-abc123"));
        assertTrue(env.contains("HIVEKEEPER_AGENT_DOMAIN=agents.gf2.in"));
        assertTrue(env.contains("HIVEKEEPER_AGENT_STORE_PASSWORD=d41d8cd98f00b204e9800998"));
        assertTrue(env.contains("HIVEKEEPER_VAULT_KEY=c2VjcmV0LWtleS1oZXJlLXBhZGRlZC10by0zMg=="));
    }

    @Test
    void pinsTheImageTagToTheGatewaysVersionRatherThanTheExamplesStaleOne() {
        String env = AgentInstallBundle.envFile(inputs(), ENV_TEMPLATE);

        assertTrue(env.contains("HIVEKEEPER_TAG=0.16.1"));
        assertFalse(env.contains("0.9.0"), "the example's stale tag must not survive into a real bundle");
    }

    @Test
    void keepsCommentsAndUntouchedDefaults() {
        String env = AgentInstallBundle.envFile(inputs(), ENV_TEMPLATE);

        // The comments are the documentation the operator reads while editing the file.
        assertTrue(env.contains("# Pin the SAME version as the gateway."));
        assertTrue(env.contains("HIVEKEEPER_REGISTRY=ghcr.io/ggfto"));
        assertTrue(env.contains("HIVEKEEPER_DEFAULT_USER=admin"));
        // A commented-out opt-in stays commented out: enabling it is a decision, not a default.
        assertTrue(env.contains("# CONTAINER_SOCK=/run/user/1000/podman/podman.sock"));
    }

    @Test
    void leavesTheCloudflareServiceTokenBlankBecauseTheGatewayCannotKnowIt() {
        String env = AgentInstallBundle.envFile(inputs(), ENV_TEMPLATE);

        assertTrue(env.contains("CF_ACCESS_CLIENT_ID=\n"));
        assertTrue(env.contains("CF_ACCESS_CLIENT_SECRET=\n"));
    }

    @Test
    void zipCarriesTheComposeTheFilledEnvTheCaAndAReadme() throws Exception {
        Map<String, String> files = entries(AgentInstallBundle.zip(inputs(), COMPOSE, ENV_TEMPLATE));

        assertEquals(COMPOSE, files.get("docker-compose.yml"));
        assertTrue(files.get(".env").contains("HIVEKEEPER_ENROLLMENT_TOKEN=enroll-abc123"));
        assertTrue(files.get("ca.pem").startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(files.get("ca.pem").endsWith("\n"), "a PEM file without a trailing newline trips some parsers");
        assertTrue(files.get("README.txt").contains("lab-agent"));
    }

    @Test
    void readmeSpellsOutTheUrlsAndTheOneThingLeftToFill() throws Exception {
        Map<String, String> files = entries(AgentInstallBundle.zip(inputs(), COMPOSE, ENV_TEMPLATE));
        String readme = files.get("README.txt");

        assertTrue(readme.contains("wss://agents.gf2.in:9443/agent"));
        assertTrue(readme.contains("https://agents.gf2.in:9443"));
        assertTrue(readme.contains("CF_ACCESS_CLIENT_ID"));
        // The vault key is unrecoverable and the operator has no other prompt to back it up.
        assertTrue(readme.contains("HIVEKEEPER_VAULT_KEY"));
    }

    @Test
    void withoutACaTheBundleOmitsCaPemAndSaysWhereToGetOne() throws Exception {
        AgentInstallBundle.Inputs noCa = new AgentInstallBundle.Inputs("lab-agent", "t", "agents.gf2.in", null,
                "0.16.1", "pw", "key");
        Map<String, String> files = entries(AgentInstallBundle.zip(noCa, COMPOSE, ENV_TEMPLATE));

        assertFalse(files.containsKey("ca.pem"), "an empty ca.pem would look like a valid one and fail at TLS");
        assertTrue(files.get("README.txt").contains("pki-init"));
    }

    @Test
    void sameInputsProduceByteIdenticalZips() throws Exception {
        // No wall-clock timestamps in the entries: a bundle that differs run to run cannot be diffed or cached.
        assertArrayEquals(AgentInstallBundle.zip(inputs(), COMPOSE, ENV_TEMPLATE),
                AgentInstallBundle.zip(inputs(), COMPOSE, ENV_TEMPLATE));
    }

    @Test
    void theRealPackagedTemplatesAreOnTheClasspath() throws Exception {
        // Guards the build-time copy from deploy/portainer: if that wiring breaks, the endpoint 500s at runtime
        // and nothing else would catch it.
        String compose = AgentInstallBundle.resource("docker-compose.yml");
        String env = AgentInstallBundle.resource("env.template");

        assertTrue(compose.contains("hivekeeper-agent"));
        assertTrue(env.contains("HIVEKEEPER_ENROLLMENT_TOKEN"));
        // And they are the real files, not stubs: the compose must carry the cloudflared sidecar the bundle
        // promises, or `docker compose up` on the operator's machine brings up an agent with no uplink.
        assertTrue(compose.contains("cloudflared"));
    }

    @Test
    void fillingTheRealTemplateLeavesNothingTheAgentNeedsBlank() throws Exception {
        // The miniature above proves the substitution rule; this proves it against the file that actually ships.
        // Every one of these blank in a delivered bundle is an agent that starts and then fails — an empty
        // gateway URL looks like a network fault, and an empty vault key silently writes credentials in plaintext.
        String env = AgentInstallBundle.envFile(inputs(), AgentInstallBundle.resource("env.template"));

        for (String key : List.of("HIVEKEEPER_AGENT_ID", "HIVEKEEPER_ENROLLMENT_TOKEN", "HIVEKEEPER_AGENT_DOMAIN",
                "HIVEKEEPER_TAG", "HIVEKEEPER_AGENT_STORE_PASSWORD", "HIVEKEEPER_VAULT_KEY")) {
            assertTrue(env.contains(key + "="), key + " is missing from the shipped env template");
            assertFalse(env.contains(key + "=\n"), key + " was left blank in the filled bundle");
        }
    }
}
