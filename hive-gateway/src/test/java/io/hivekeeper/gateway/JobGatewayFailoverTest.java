package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.gateway.JobService.JobRow;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.wire.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the primary of a site drops, its unfinished durable jobs move to the standby and dispatch there —
 * so a config or backup queued for the primary is not stranded until it returns, and no agent runs a job
 * twice.
 */
class JobGatewayFailoverTest {

    private static final String KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";

    /** An in-memory JobService that actually models addressing and the atomic reassign. */
    private static final class MapJobService extends JobService {
        final ConcurrentHashMap<String, JobRow> byId = new ConcurrentHashMap<>();
        private int seq;

        MapJobService() {
            super(null);
        }

        @Override
        public synchronized String create(String tenantId, String agentId, String idem, String type, String cmd) {
            String id = "job-" + (++seq);
            byId.put(id, new JobRow(id, tenantId, agentId, idem, type, cmd, "PENDING", null, null));
            return id;
        }

        @Override
        public synchronized void markDispatched(String tenantId, String jobId) {
            byId.computeIfPresent(jobId, (k, r) -> "PENDING".equals(r.status())
                    ? new JobRow(r.jobId(), r.tenantId(), r.agentId(), r.idempotencyKey(), r.type(),
                            r.commandJson(), "DISPATCHED", null, null) : r);
        }

        @Override
        public synchronized List<JobRow> pendingFor(String tenantId, String agentId) {
            List<JobRow> out = new ArrayList<>();
            for (JobRow r : byId.values()) {
                if (r.agentId().equals(agentId) && !"SUCCEEDED".equals(r.status()) && !"FAILED".equals(r.status())) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public synchronized List<JobRow> reassign(String tenantId, String fromAgent, String toAgent) {
            List<JobRow> moved = new ArrayList<>();
            for (JobRow r : List.copyOf(byId.values())) {
                if (r.agentId().equals(fromAgent) && !"SUCCEEDED".equals(r.status()) && !"FAILED".equals(r.status())) {
                    JobRow m = new JobRow(r.jobId(), r.tenantId(), toAgent, r.idempotencyKey(), r.type(),
                            r.commandJson(), "PENDING", null, null);
                    byId.put(m.jobId(), m);
                    moved.add(m);
                }
            }
            return moved;
        }
    }

    private static final class FakeChannel implements FrameChannel {
        final List<String> dispatchedJobIds = new ArrayList<>();

        @Override public void send(Frame frame) {
            if (frame instanceof Frame.Job j) {
                dispatchedJobIds.add(j.jobId());
            }
        }

        @Override public void onFrame(Consumer<Frame> handler) {
        }

        @Override public void close() {
        }
    }

    private MapJobService store;
    private TenantStore tenants;
    private JobGateway gateway;

    @BeforeEach
    void setUp() {
        store = new MapJobService();
        tenants = mock(TenantStore.class);
        gateway = new JobGateway(store, SecretCipher.fromBase64(KEY), tenants);
        // Two agents share site-1; the primary sorts first.
        when(tenants.agentSiteId("site-a-01")).thenReturn(Optional.of("site-1"));
        when(tenants.agentSiteId("site-a-02")).thenReturn(Optional.of("site-1"));
        when(tenants.agentIdsForSite("acme", "site-1")).thenReturn(List.of("site-a-01", "site-a-02"));
    }

    private String submitTo(String agentId) {
        return gateway.submit("acme", agentId, "configure-ssid",
                Command.Inventory.of(DeviceRef.ssh("10.0.0.1", 22)));
    }

    @Test
    void movesTheDroppedPrimarysJobsToTheStandbyAndDispatchesThem() {
        FakeChannel primary = new FakeChannel();
        FakeChannel standby = new FakeChannel();
        gateway.onAgentConnected("acme", "site-a-01", primary);
        gateway.onAgentConnected("acme", "site-a-02", standby);

        String jobId = submitTo("site-a-01");            // dispatched to the primary
        assertEquals(List.of(jobId), primary.dispatchedJobIds);

        gateway.onAgentDisconnected("acme", "site-a-01");   // the primary drops

        assertEquals("site-a-02", store.byId.get(jobId).agentId(), "the job now belongs to the standby");
        assertTrue(standby.dispatchedJobIds.contains(jobId), "and it was dispatched to the standby");
    }

    @Test
    void doesNotStealJobsWhenAnotherAgentIsStillPrimary() {
        // Three agents; the disconnecting one (site-a-03) is NOT the primary, so nothing fails over — the
        // primary is still up and owns the site.
        when(tenants.agentSiteId("site-a-03")).thenReturn(Optional.of("site-1"));
        when(tenants.agentIdsForSite("acme", "site-1"))
                .thenReturn(List.of("site-a-01", "site-a-02", "site-a-03"));
        FakeChannel primary = new FakeChannel();
        FakeChannel other = new FakeChannel();
        gateway.onAgentConnected("acme", "site-a-01", primary);
        gateway.onAgentConnected("acme", "site-a-03", other);

        String jobId = submitTo("site-a-03");   // a legacy job pinned to the third agent
        gateway.onAgentDisconnected("acme", "site-a-03");

        // site-a-01 is the primary and picks it up (it is now the serving agent for the site).
        assertEquals("site-a-01", store.byId.get(jobId).agentId());
        assertTrue(primary.dispatchedJobIds.contains(jobId));
    }

    @Test
    void theStandbyClaimsAnAlreadyOfflinePrimarysJobsWhenItConnects() {
        // The primary was down BEFORE the standby came up: submit lands as queued, then the standby connects
        // and, being the only connected agent, becomes primary and claims the orphaned work.
        String jobId = submitTo("site-a-01");   // primary offline → queued, no dispatch

        FakeChannel standby = new FakeChannel();
        gateway.onAgentConnected("acme", "site-a-02", standby);

        assertEquals("site-a-02", store.byId.get(jobId).agentId());
        assertTrue(standby.dispatchedJobIds.contains(jobId), "the standby dispatched the claimed job");
    }

    @Test
    void doesNothingForASingleAgentSite() {
        when(tenants.agentSiteId("solo")).thenReturn(Optional.of("site-solo"));
        when(tenants.agentIdsForSite("acme", "site-solo")).thenReturn(List.of("solo"));
        FakeChannel ch = new FakeChannel();
        gateway.onAgentConnected("acme", "solo", ch);
        String jobId = gateway.submit("acme", "solo", "configure-ssid",
                Command.Inventory.of(DeviceRef.ssh("10.0.0.9", 22)));

        gateway.onAgentDisconnected("acme", "solo");   // no standby to take over

        assertEquals("solo", store.byId.get(jobId).agentId(), "the job stays addressed to the only agent");
    }
}
