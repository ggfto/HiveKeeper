package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.gateway.tenant.Tenant;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The control-plane REST API, scoped by tenant via the {@code X-Tenant-Key} header (v1 operator auth;
 * production = OIDC). It dispatches work to a connected agent through that agent's {@link RemoteEngine},
 * sending ONLY intent (host) — credentials live on the agent. All registry lookups are tenant-scoped,
 * so one tenant can never reach another's agents.
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

    /** Submit a durable job: {@code type} is inventory|backup|discover, with host (or cidr). */
    public record JobRequest(String type, String host, String cidr, Integer port) {
    }

    public record JobSubmitted(String jobId, String status) {
    }

    public record ApiError(String error, String detail) {
    }

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;
    private final TenantStore tenants;
    private final Optional<OperationLog> operationLog;
    private final Optional<JobGateway> jobGateway;
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "gw-sse");
        thread.setDaemon(true);
        return thread;
    });

    public GatewayController(AgentRegistry registry, TenantStore tenants,
                             Optional<OperationLog> operationLog, Optional<JobGateway> jobGateway) {
        this.registry = registry;
        this.tenants = tenants;
        this.operationLog = operationLog;
        this.jobGateway = jobGateway;
    }

    /** Submit a DURABLE job: it is persisted, dispatched if the agent is connected, and redelivered when
     *  the agent reconnects — so it survives a transient disconnect. Requires the postgres profile. */
    @PostMapping("/api/agents/{agentId}/jobs")
    public ResponseEntity<String> submitJob(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                            @PathVariable String agentId, @RequestBody JobRequest req) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        if (jobGateway.isEmpty()) {
            return status(501, new ApiError("not_supported", "durable jobs require the 'postgres' profile"));
        }
        Command command;
        try {
            command = toCommand(req);
        } catch (IllegalArgumentException e) {
            return status(400, new ApiError("bad_request", e.getMessage()));
        }
        String jobId = jobGateway.get().submit(tenant.get().tenantId(), agentId, req.type(), command);
        return json(new JobSubmitted(jobId, "submitted"));
    }

    @GetMapping("/api/jobs/{jobId}")
    public ResponseEntity<String> jobStatus(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                            @PathVariable String jobId) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        if (jobGateway.isEmpty()) {
            return status(501, new ApiError("not_supported", "durable jobs require the 'postgres' profile"));
        }
        return jobGateway.get().get(tenant.get().tenantId(), jobId)
                .map(this::json)
                .orElseGet(() -> status(404, new ApiError("job_not_found", jobId)));
    }

    private static Command toCommand(JobRequest req) {
        int port = req.port() == null ? 22 : req.port();
        return switch (req.type() == null ? "" : req.type()) {
            case "inventory" -> Command.Inventory.of(DeviceRef.ssh(requireHost(req), port));
            case "backup" -> Command.BackupConfig.of(DeviceRef.ssh(requireHost(req), port));
            case "discover" -> Command.Discover.of(requireCidr(req), port, 800);
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

    /** Live inventory: streams the agent's progress events as SSE, then the final result. The agent's
     *  events reach the RemoteEngine's EventSink as they arrive, so the browser sees real progress. */
    @PostMapping("/api/agents/{agentId}/inventory/stream")
    public SseEmitter inventoryStream(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                      @PathVariable String agentId, @RequestBody DeviceRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);

        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return completeWithError(emitter, new ApiError("unauthorized", "missing or invalid X-Tenant-Key"));
        }
        Optional<RemoteEngine> engine = registry.engine(tenant.get().tenantId(), agentId);
        if (engine.isEmpty()) {
            return completeWithError(emitter, new ApiError("agent_not_connected", agentId));
        }
        if (req == null || req.host() == null || req.host().isBlank()) {
            return completeWithError(emitter, new ApiError("bad_request", "host is required"));
        }

        String tenantId = tenant.get().tenantId();
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
    public ResponseEntity<String> operations(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        if (operationLog.isEmpty()) {
            return json(java.util.List.of());  // no DB profile -> no audit log
        }
        return json(operationLog.get().list(tenant.get().tenantId()));
    }

    @GetMapping("/api/agents")
    public ResponseEntity<String> agents(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        return json(registry.agentIds(tenant.get().tenantId()));
    }

    // These do an agent round-trip (seconds); returning a Callable lets Spring run them on a bounded
    // async executor (see AsyncConfig) so the servlet container threads are not held while waiting.

    @PostMapping("/api/agents/{agentId}/inventory")
    public Callable<ResponseEntity<String>> inventory(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DeviceRequest req) {
        return () -> dispatch(apiKey, agentId, "inventory", req, Command.Inventory::of);
    }

    @PostMapping("/api/agents/{agentId}/backup")
    public Callable<ResponseEntity<String>> backup(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DeviceRequest req) {
        return () -> dispatch(apiKey, agentId, "backup", req, Command.BackupConfig::of);
    }

    @PostMapping("/api/agents/{agentId}/discover")
    public Callable<ResponseEntity<String>> discover(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DiscoverRequest req) {
        return () -> doDiscover(apiKey, agentId, req);
    }

    // Config WRITES. The cloud sends intent (host + the change); the agent holds credentials and the
    // generated CLI is produced on the agent's in-process driver — same Command the CLI builds from argv.

    @PostMapping("/api/agents/{agentId}/configure-ssid")
    public Callable<ResponseEntity<String>> configureSsid(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody SsidRequest req) {
        return () -> {
            SsidSpec spec;
            try {
                spec = req.remove() ? SsidSpec.removal(req.name()) : SsidSpec.create(req.name(), req.psk(), req.vlan());
            } catch (IllegalArgumentException e) {
                return status(400, new ApiError("bad_request", e.getMessage()));
            }
            return dispatch(apiKey, agentId, "configure-ssid", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.ConfigureSsid.of(ref, spec));
        };
    }

    @PostMapping("/api/agents/{agentId}/configure-hive")
    public Callable<ResponseEntity<String>> configureHive(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody HiveRequest req) {
        return () -> {
            HiveSpec spec;
            try {
                spec = new HiveSpec(req.name(), req.password(), req.boundInterface());
            } catch (IllegalArgumentException e) {
                return status(400, new ApiError("bad_request", e.getMessage()));
            }
            return dispatch(apiKey, agentId, "configure-hive", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.ConfigureHive.of(ref, spec));
        };
    }

    @PostMapping("/api/agents/{agentId}/reboot")
    public Callable<ResponseEntity<String>> reboot(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DeviceRequest req) {
        return () -> dispatch(apiKey, agentId, "reboot", req, Command.Reboot::of);
    }

    private ResponseEntity<String> doDiscover(String apiKey, String agentId, DiscoverRequest req) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        Optional<RemoteEngine> engine = registry.engine(tenant.get().tenantId(), agentId);
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
            record(tenant.get().tenantId(), agentId, "discover", req.cidr().trim(),
                    result.getClass().getSimpleName());
            return json(result);
        } catch (Exception e) {
            log.warn("discover via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private ResponseEntity<String> dispatch(String apiKey, String agentId, String opType, DeviceRequest req,
                                            Function<DeviceRef, Command> commandFactory) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return unauthorized();
        }
        Optional<RemoteEngine> engine = registry.engine(tenant.get().tenantId(), agentId);
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
            record(tenant.get().tenantId(), agentId, opType, req.host().trim(), result.getClass().getSimpleName());
            return json(result);
        } catch (Exception e) {
            log.warn("dispatch to agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void record(String tenantId, String agentId, String opType, String host, String summary) {
        operationLog.ifPresent(log -> log.record(tenantId, agentId, opType, host, summary));
    }

    private ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(codec.toJson(value));
    }

    private ResponseEntity<String> unauthorized() {
        return status(401, new ApiError("unauthorized", "missing or invalid X-Tenant-Key"));
    }

    private ResponseEntity<String> status(int code, ApiError error) {
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(codec.toJson(error));
    }
}
