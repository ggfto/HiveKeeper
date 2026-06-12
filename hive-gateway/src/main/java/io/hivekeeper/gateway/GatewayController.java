package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.crypto.Secrets;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The control-plane REST API. Every endpoint resolves the caller via {@link AccessGuard} (a user's bearer
 * JWT + active org, or the {@code X-Tenant-Key} service principal) and authorizes the action: operations on
 * an agent are scoped to that agent's SITE — reads (inventory/backup/discover) need {@code viewer}, writes
 * (config/reboot) need {@code operator}; org-wide reads (agent list, operations, job status) need
 * {@code viewer} on the org. Credentials never leave the agent; the cloud sends only intent.
 */
@RestController
@Slf4j
public class GatewayController {

    /** Cloud sends intent only — host (and optional port). Credentials live on the agent. */
    public record DeviceRequest(String host, Integer port) {
    }

    /** Ask the agent to sweep a subnet of its own LAN. */
    public record DiscoverRequest(String cidr, Integer port, Integer timeoutMillis) {
    }

    /** Create or remove a WPA2-PSK SSID on a device behind the agent. */
    public record SsidRequest(String host, Integer port, String name, String psk, Integer vlan, boolean remove) {
    }

    /** Join a device behind the agent to a hive/mesh. */
    public record HiveRequest(String host, Integer port, String name, String password, String boundInterface) {
    }

    /** Submit a durable job. {@code type} is inventory|backup|discover|configure-ssid|configure-hive;
     *  the write types carry the secret fields, which are encrypted at rest by the {@link JobGateway}. */
    public record JobRequest(String type, String host, String cidr, Integer port,
                             String name, String psk, Integer vlan, boolean remove, String password) {
    }

    public record JobSubmitted(String jobId, String status) {
    }

    public record ApiError(String error, String detail) {
    }

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;
    private final AccessGuard guard;
    private final TenantStore tenants;
    private final Optional<OperationLog> operationLog;
    private final Optional<JobGateway> jobGateway;
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "gw-sse");
        thread.setDaemon(true);
        return thread;
    });

    public GatewayController(AgentRegistry registry, AccessGuard guard, TenantStore tenants,
                             Optional<OperationLog> operationLog, Optional<JobGateway> jobGateway) {
        this.registry = registry;
        this.guard = guard;
        this.tenants = tenants;
        this.operationLog = operationLog;
        this.jobGateway = jobGateway;
    }

    /** Submit a DURABLE job: persisted, dispatched if the agent is connected, and redelivered on reconnect.
     *  Requires the postgres profile. The required role follows the job type (writes need operator). */
    @PostMapping("/api/agents/{agentId}/jobs")
    public ResponseEntity<String> submitJob(@PathVariable String agentId, @RequestBody JobRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, roleForJobType(req.type()), agentScope(p, agentId));
        if (jobGateway.isEmpty()) {
            return status(501, new ApiError("not_supported", "durable jobs require the 'postgres' profile"));
        }
        Command command;
        try {
            command = toCommand(req);
        } catch (IllegalArgumentException e) {
            return status(400, new ApiError("bad_request", e.getMessage()));
        }
        String jobId = jobGateway.get().submit(p.tenantId(), agentId, req.type(), command);
        return json(new JobSubmitted(jobId, "submitted"));
    }

    @GetMapping("/api/jobs/{jobId}")
    public ResponseEntity<String> jobStatus(@PathVariable String jobId) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        if (jobGateway.isEmpty()) {
            return status(501, new ApiError("not_supported", "durable jobs require the 'postgres' profile"));
        }
        return jobGateway.get().view(p.tenantId(), jobId)
                .map(this::json)
                .orElseGet(() -> status(404, new ApiError("job_not_found", jobId)));
    }

    private static Role roleForJobType(String type) {
        return "configure-ssid".equals(type) || "configure-hive".equals(type) ? Role.OPERATOR : Role.VIEWER;
    }

    private static Command toCommand(JobRequest req) {
        int port = req.port() == null ? 22 : req.port();
        return switch (req.type() == null ? "" : req.type()) {
            case "inventory" -> Command.Inventory.of(DeviceRef.ssh(requireHost(req), port));
            case "backup" -> Command.BackupConfig.of(DeviceRef.ssh(requireHost(req), port));
            case "discover" -> Command.Discover.of(requireCidr(req), port, 800);
            case "configure-ssid" -> Command.ConfigureSsid.of(DeviceRef.ssh(requireHost(req), port),
                    req.remove() ? SsidSpec.removal(req.name()) : SsidSpec.create(req.name(), req.psk(), req.vlan()));
            case "configure-hive" -> Command.ConfigureHive.of(DeviceRef.ssh(requireHost(req), port),
                    new HiveSpec(req.name(), req.password(), null));
            default -> throw new IllegalArgumentException("unknown job type: " + req.type());
        };
    }

    private static String requireHost(JobRequest req) {
        if (req.host() == null || req.host().isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        return req.host().trim();
    }

    private static String requireCidr(JobRequest req) {
        if (req.cidr() == null || req.cidr().isBlank()) {
            throw new IllegalArgumentException("cidr is required");
        }
        return req.cidr().trim();
    }

    @PreDestroy
    void shutdown() {
        sseExecutor.shutdownNow();
    }

    /** Live inventory: streams the agent's progress events as SSE, then the final result. */
    @PostMapping("/api/agents/{agentId}/inventory/stream")
    public SseEmitter inventoryStream(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, agentScope(p, agentId));   // throws -> rendered as 401/403, not a stream

        SseEmitter emitter = new SseEmitter(120_000L);
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return completeWithError(emitter, new ApiError("agent_not_connected", agentId));
        }
        if (req == null || req.host() == null || req.host().isBlank()) {
            return completeWithError(emitter, new ApiError("bad_request", "host is required"));
        }

        String tenantId = p.tenantId();
        String host = req.host().trim();
        int port = req.port() == null ? 22 : req.port();
        sseExecutor.submit(() -> {
            try {
                EventSink sink = event -> sendSse(emitter, "event", codec.toJson(event));
                Result result = engine.get().execute(Command.Inventory.of(DeviceRef.ssh(host, port)), sink);
                record(tenantId, agentId, "inventory", host, result.getClass().getSimpleName());
                sendSse(emitter, "result", codec.toJson(result));
                emitter.complete();
            } catch (Exception e) {
                log.warn("inventory stream via '{}' failed: {}", agentId, e.getMessage());
                sendSse(emitter, "error", codec.toJson(
                        new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private SseEmitter completeWithError(SseEmitter emitter, ApiError error) {
        sendSse(emitter, "error", codec.toJson(error));
        emitter.complete();
        return emitter;
    }

    private void sendSse(SseEmitter emitter, String name, String json) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON));
            }
        } catch (IOException e) {
            log.debug("sse send failed: {}", e.getMessage());
        }
    }

    @GetMapping("/api/operations")
    public ResponseEntity<String> operations() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        if (operationLog.isEmpty()) {
            return json(List.of());  // no DB profile -> no audit log
        }
        return json(operationLog.get().list(p.tenantId()));
    }

    /** Lists the connected agents the caller can actually see — filtered to those whose site they may view. */
    @GetMapping("/api/agents")
    public ResponseEntity<String> agents() {
        Principal p = guard.authenticate();
        List<String> visible = registry.agentIds(p.tenantId()).stream()
                .filter(agentId -> guard.allows(p, Role.VIEWER, siteScope(agentId)))
                .toList();
        return json(visible);
    }

    // These do an agent round-trip (seconds); returning a Callable runs them on a bounded async executor
    // (see AsyncConfig). Auth happens HERE on the request thread (headers/JWT available) — the Callable then
    // runs with the resolved principal.

    @PostMapping("/api/agents/{agentId}/inventory")
    public Callable<ResponseEntity<String>> inventory(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, agentScope(p, agentId));
        return () -> dispatch(p, agentId, "inventory", req, Command.Inventory::of);
    }

    @PostMapping("/api/agents/{agentId}/backup")
    public Callable<ResponseEntity<String>> backup(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, agentScope(p, agentId));
        return () -> dispatch(p, agentId, "backup", req, Command.BackupConfig::of);
    }

    @PostMapping("/api/agents/{agentId}/discover")
    public Callable<ResponseEntity<String>> discover(@PathVariable String agentId, @RequestBody DiscoverRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, agentScope(p, agentId));
        return () -> doDiscover(p, agentId, req);
    }

    // Config WRITES need operator. The cloud sends intent (host + the change); the agent holds credentials.

    @PostMapping("/api/agents/{agentId}/configure-ssid")
    public Callable<ResponseEntity<String>> configureSsid(@PathVariable String agentId, @RequestBody SsidRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> {
            SsidSpec spec;
            try {
                spec = req.remove() ? SsidSpec.removal(req.name()) : SsidSpec.create(req.name(), req.psk(), req.vlan());
            } catch (IllegalArgumentException e) {
                return status(400, new ApiError("bad_request", e.getMessage()));
            }
            return dispatch(p, agentId, "configure-ssid", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.ConfigureSsid.of(ref, spec));
        };
    }

    @PostMapping("/api/agents/{agentId}/configure-hive")
    public Callable<ResponseEntity<String>> configureHive(@PathVariable String agentId, @RequestBody HiveRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> {
            HiveSpec spec;
            try {
                spec = new HiveSpec(req.name(), req.password(), req.boundInterface());
            } catch (IllegalArgumentException e) {
                return status(400, new ApiError("bad_request", e.getMessage()));
            }
            return dispatch(p, agentId, "configure-hive", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.ConfigureHive.of(ref, spec));
        };
    }

    @PostMapping("/api/agents/{agentId}/reboot")
    public Callable<ResponseEntity<String>> reboot(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> dispatch(p, agentId, "reboot", req, Command.Reboot::of);
    }

    private ResponseEntity<String> doDiscover(Principal p, String agentId, DiscoverRequest req) {
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        if (req == null || req.cidr() == null || req.cidr().isBlank()) {
            return status(400, new ApiError("bad_request", "cidr is required"));
        }
        try {
            Command cmd = Command.Discover.of(req.cidr().trim(),
                    req.port() == null ? 22 : req.port(),
                    req.timeoutMillis() == null ? 800 : req.timeoutMillis());
            Result result = engine.get().execute(cmd, EventSink.NOOP);
            record(p.tenantId(), agentId, "discover", req.cidr().trim(), result.getClass().getSimpleName());
            return json(result);
        } catch (Exception e) {
            log.warn("discover via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private ResponseEntity<String> dispatch(Principal p, String agentId, String opType, DeviceRequest req,
                                            Function<DeviceRef, Command> commandFactory) {
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            // 404 whether the agent is offline OR belongs to another tenant — no cross-tenant leakage.
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        if (req == null || req.host() == null || req.host().isBlank()) {
            return status(400, new ApiError("bad_request", "host is required"));
        }
        try {
            DeviceRef ref = DeviceRef.ssh(req.host().trim(), req.port() == null ? 22 : req.port());
            Result result = engine.get().execute(commandFactory.apply(ref), EventSink.NOOP);
            record(p.tenantId(), agentId, opType, req.host().trim(), result.getClass().getSimpleName());
            // A config-write result echoes the applied CLI lines; mask secrets before they reach the browser.
            return json(Secrets.redactResult(result));
        } catch (Exception e) {
            log.warn("dispatch to agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** The scope an agent operation is authorized against: the agent's site (its physical LAN), or the org
     *  if the agent is not bound to a site. Verifies the agent belongs to the CALLER's tenant first — agent
     *  ids are globally unique and the enrollment table has no RLS, so without this check a request could be
     *  authorized against another tenant's site (and submitJob would persist a job row aimed at it). */
    private ResourceScope agentScope(Principal p, String agentId) {
        boolean ownsAgent = tenants.enrollmentByAgentId(agentId)
                .map(enrollment -> enrollment.tenantId().equals(p.tenantId()))
                .orElse(false);
        if (!ownsAgent) {
            throw new AccessException(404, "agent_not_found", agentId);
        }
        return siteScope(agentId);
    }

    /** The agent's site scope (no tenant check) — used to filter the agent list, which is already scoped to
     *  the caller's tenant by {@code registry.agentIds}. */
    private ResourceScope siteScope(String agentId) {
        return tenants.agentSiteId(agentId).map(ResourceScope::site).orElseGet(ResourceScope::org);
    }

    private void record(String tenantId, String agentId, String opType, String host, String summary) {
        operationLog.ifPresent(log -> log.record(tenantId, agentId, opType, host, summary));
    }

    private ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(codec.toJson(value));
    }

    private ResponseEntity<String> status(int code, ApiError error) {
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(codec.toJson(error));
    }
}
