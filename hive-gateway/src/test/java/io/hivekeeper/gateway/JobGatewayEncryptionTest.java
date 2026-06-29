package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.gateway.JobGateway.JobView;
import io.hivekeeper.gateway.JobService.JobRow;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.wire.JsonCodec;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the at-rest guarantee end-to-end without a database: submitted config-write commands AND their
 * results are stored as ciphertext that does not contain the plaintext secret; redelivery and the
 * operator-facing view() decrypt correctly and mask secrets; and a single undecryptable row does not
 * sink the rest of redelivery.
 */
class JobGatewayEncryptionTest {

    private static final String KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";
    private static final String PSK = "hunter2-very-secret-psk";
    private static final String HIVE_PASSWORD = "mesh-secret-xyz";

    /** A JobService that keeps the row in memory so the test can inspect exactly what was "persisted". */
    private static final class CapturingJobService extends JobService {
        String storedCommandJson;
        String storedResultJson;
        String storedError;
        String type = "configure-ssid";
        List<JobRow> pendingOverride;

        CapturingJobService() {
            super(null);   // overridden methods never touch the (null) JdbcTemplate
        }

        @Override
        public String create(String tenantId, String agentId, String idempotencyKey, String type, String commandJson) {
            this.storedCommandJson = commandJson;
            this.type = type;
            return "job-1";
        }

        @Override
        public void markDispatched(String tenantId, String jobId) {
        }

        @Override
        public void complete(String tenantId, String jobId, boolean ok, String resultJson, String error) {
            this.storedResultJson = resultJson;
            this.storedError = error;
        }

        @Override
        public List<JobRow> pendingFor(String tenantId, String agentId) {
            if (pendingOverride != null) {
                return pendingOverride;
            }
            return List.of(new JobRow("job-1", tenantId, agentId, "idem-1", type,
                    storedCommandJson, "PENDING", null, null));
        }

        @Override
        public Optional<JobRow> get(String tenantId, String jobId) {
            return Optional.of(new JobRow("job-1", tenantId, "agent", "idem-1", type,
                    storedCommandJson, "SUCCEEDED", storedResultJson, storedError));
        }
    }

    private static final class CapturingChannel implements FrameChannel {
        final List<Frame> sent = new ArrayList<>();

        @Override public void send(Frame frame) {
            sent.add(frame);
        }

        @Override public void onFrame(Consumer<Frame> handler) {
        }

        @Override public void close() {
        }
    }

    private JobGateway gateway(CapturingJobService store) {
        return new JobGateway(store, SecretCipher.fromBase64(KEY));
    }

    private Command.ConfigureSsid ssidCommand() {
        return Command.ConfigureSsid.of(DeviceRef.ssh("10.0.0.1"), SsidSpec.create("HK", PSK, 5));
    }

    private Command.ConfigureHive hiveCommand() {
        return Command.ConfigureHive.of(DeviceRef.ssh("10.0.0.1"), new HiveSpec("lab", HIVE_PASSWORD, null));
    }

    /** A result that echoes a CLI line embedding the PSK (as a real ConfigApplied does). */
    private Result.ConfigApplied secretResult() {
        return new Result.ConfigApplied(UUID.randomUUID(), DeviceId.of("10.0.0.1"),
                List.of("security-object HK security protocol-suite wpa2-aes-psk ascii-key " + PSK, "ssid HK"),
                List.of("ok", ""), true);
    }

    // -- command at rest --------------------------------------------------------

    @Test
    void persistedSsidCommandIsCiphertextWithoutThePlaintextSecret() {
        CapturingJobService store = new CapturingJobService();
        gateway(store).submit("acme", "agent", "configure-ssid", ssidCommand());

        assertNotNull(store.storedCommandJson);
        assertTrue(store.storedCommandJson.startsWith("gcm1:"), "command_json must be an encrypted token");
        assertFalse(store.storedCommandJson.contains(PSK), "the plaintext PSK must never be written at rest");
    }

    @Test
    void persistedHiveCommandIsCiphertextWithoutThePlaintextSecret() {
        CapturingJobService store = new CapturingJobService();
        gateway(store).submit("acme", "agent", "configure-hive", hiveCommand());

        assertTrue(store.storedCommandJson.startsWith("gcm1:"));
        assertFalse(store.storedCommandJson.contains(HIVE_PASSWORD), "hive password must never be written at rest");
    }

    @Test
    void redeliveryDecryptsTheSsidCommandBackToTheAgent() {
        CapturingJobService store = new CapturingJobService();
        JobGateway gw = gateway(store);
        gw.submit("acme", "agent", "configure-ssid", ssidCommand());

        CapturingChannel channel = new CapturingChannel();
        gw.onAgentConnected("acme", "agent", channel);

        Command.ConfigureSsid redelivered = (Command.ConfigureSsid) firstJob(channel).command();
        assertEquals(PSK, redelivered.spec().passphrase(), "redelivery must reconstruct the exact command");
    }

    @Test
    void redeliveryDecryptsTheHiveCommandBackToTheAgent() {
        CapturingJobService store = new CapturingJobService();
        JobGateway gw = gateway(store);
        gw.submit("acme", "agent", "configure-hive", hiveCommand());

        CapturingChannel channel = new CapturingChannel();
        gw.onAgentConnected("acme", "agent", channel);

        Command.ConfigureHive redelivered = (Command.ConfigureHive) firstJob(channel).command();
        assertEquals(HIVE_PASSWORD, redelivered.spec().password());
    }

    // -- result at rest + operator view -----------------------------------------

    @Test
    void persistedResultIsCiphertextWithoutThePlaintextSecret() {
        CapturingJobService store = new CapturingJobService();
        gateway(store).onFrame("acme", new Frame.JobResult("job-1", secretResult()));

        assertNotNull(store.storedResultJson);
        assertTrue(store.storedResultJson.startsWith("gcm1:"), "result_json must be encrypted at rest");
        assertFalse(store.storedResultJson.contains(PSK), "the result echoes the PSK and must not be stored in clear");
    }

    @Test
    void viewDecryptsAndStructurallyRedactsTheResult() {
        CapturingJobService store = new CapturingJobService();
        JobGateway gw = gateway(store);
        gw.onFrame("acme", new Frame.JobResult("job-1", secretResult()));   // stores encrypted result

        JobView view = gw.view("acme", "job-1").orElseThrow();

        assertNotNull(view.resultJson());
        assertFalse(view.resultJson().startsWith("gcm1:"), "operator must see a decrypted result");
        assertFalse(view.resultJson().contains(PSK), "the decrypted result must be redacted");
        assertTrue(view.resultJson().contains("ascii-key ***"), "redaction is field-level so the JSON stays valid");
        assertTrue(view.resultJson().contains("commands"), "the result structure is preserved, not corrupted");

        // The whole serialized view must never carry the raw command blob or any secret.
        String serialized = new JsonCodec().toJson(view);
        assertFalse(serialized.contains(PSK));
        assertFalse(serialized.contains("gcm1:"));
    }

    @Test
    void viewDoesNotThrowWhenAResultCannotBeDecrypted() {
        CapturingJobService store = new CapturingJobService();
        store.storedResultJson = "gcm1:not-decryptable";   // e.g. written under a rotated key
        JobView view = gateway(store).view("acme", "job-1").orElseThrow();

        assertNotNull(view.resultJson());
        assertFalse(view.resultJson().contains(PSK));
        assertTrue(view.resultJson().contains("unavailable"), "decrypt failure degrades to a placeholder, not a 500");
    }

    @Test
    void jobFailedDetailIsMaskedBeforeItLandsInTheErrorColumn() {
        CapturingJobService store = new CapturingJobService();
        gateway(store).onFrame("acme",
                new Frame.JobFailed("job-1", "apply failed", "echoed: ascii-key " + PSK + " password s3cr3t"));

        assertNotNull(store.storedError);
        assertFalse(store.storedError.contains(PSK), "a PSK in a failure detail must not reach the error column in clear");
        assertFalse(store.storedError.contains("s3cr3t"), "a hive password in a failure detail must be masked");
        assertTrue(store.storedError.contains("ascii-key ***"));
    }

    // -- resilience -------------------------------------------------------------

    @Test
    void oneUndecryptableRowDoesNotBlockRedeliveryOfHealthyJobs() {
        CapturingJobService store = new CapturingJobService();
        JobGateway gw = gateway(store);
        gw.submit("acme", "agent", "configure-ssid", ssidCommand());   // populates a valid token

        store.pendingOverride = List.of(
                new JobRow("bad", "acme", "agent", "idem-bad", "configure-ssid", "gcm1:not-base64!!", "PENDING", null, null),
                new JobRow("good", "acme", "agent", "idem-good", "configure-ssid", store.storedCommandJson, "PENDING", null, null));

        CapturingChannel channel = new CapturingChannel();
        gw.onAgentConnected("acme", "agent", channel);   // must not throw despite the poison row

        Frame.Job job = firstJob(channel);
        assertEquals("good", job.jobId(), "the healthy job is still redelivered after the bad one is skipped");
    }

    private static Frame.Job firstJob(CapturingChannel channel) {
        return channel.sent.stream()
                .filter(Frame.Job.class::isInstance).map(Frame.Job.class::cast)
                .findFirst().orElseThrow();
    }
}
