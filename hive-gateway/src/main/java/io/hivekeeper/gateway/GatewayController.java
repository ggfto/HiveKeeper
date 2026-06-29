package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.crypto.CredentialPayload;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.core.crypto.Secrets;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** Apply raw HiveOS CLI configuration lines to a device, optionally persisting with {@code save config}.
     *  The universal config escape hatch — anything the CLI can do (hostname, radio, capwap, captive portal). */
    public record ApplyConfigRequest(String host, Integer port, List<String> commands, boolean save) {
    }

    /** Re-apply a previously captured running-config to a device by replaying its lines (additive),
     *  optionally persisting with {@code save config}. The cloud sends the config text; the agent applies it. */
    public record RestoreRequest(String host, Integer port, String runningConfig, boolean save) {
    }

    /** Upgrade a device's firmware from an image URL the AP can reach (TFTP/FTP/HTTP), optionally rebooting
     *  to activate it. LAB/UNTESTED in v0.1 — see HiveOsDriver. */
    public record FirmwareUpgradeRequest(String host, Integer port, String imageUrl, boolean reboot) {
    }

    /** Submit a durable job. {@code type} is inventory|backup|discover|configure-ssid|configure-hive;
     *  the write types carry the secret fields, which are encrypted at rest by the {@link JobGateway}. */
    public record JobRequest(String type, String host, String cidr, Integer port,
                             String name, String psk, Integer vlan, boolean remove, String password) {
    }

    public record JobSubmitted(String jobId, String status) {
    }

    /** Adopt a discovered host into the fleet: inventory it, then register a device by its real serial.
     *  {@code credRef} is the on-prem credential pointer for future ops (the agent resolves it locally). */
    public record AdoptRequest(String host, Integer port, String label, String credRef) {
    }

    /** Set (or rotate) the SSH credential HiveKeeper uses for a device. The secret is sealed to the agent's
     *  public key here and never persisted in the cloud. {@code alsoSetOnDevice} additionally changes the
     *  admin password ON the AP. {@code deviceId} (when the host is a registered device) lets the gateway
     *  pin the resulting {@code credRef} so future ops resolve it. */
    public record SetCredentialRequest(String host, Integer port, String deviceId, String credRef,
                                       String username, String password, boolean alsoSetOnDevice) {
    }

    public record CredentialSetResponse(String credRef, boolean vaultUpdated, boolean deviceUpdated) {
    }

    public record AdoptResponse(String deviceId, String serial, String model) {
    }

    /** Run a read op across every registered device in a group, a site, or (both null) the whole org. */
    public record BulkRequest(String siteId, String groupId) {
    }

    public record BulkOutcome(String deviceId, String serial, String host, String status, String detail) {
    }

    public record BulkResponse(String op, int total, int ok, int failed, List<BulkOutcome> results) {
    }

    public record ApiError(String error, String detail) {
    }

    /** Wall-clock budget for a bulk op's device loop. Kept under the MVC async timeout (120s, see AsyncConfig)
     *  minus one worst-case agent round-trip (60s, see RemoteEngine) so the LAST device we start still finishes
     *  before Spring would abort the request and discard the partial BulkResponse. */
    private static final Duration BULK_BUDGET = Duration.ofSeconds(50);

    private final JsonCodec codec = new JsonCodec();
    private final EnvelopeCipher envelope = new EnvelopeCipher();   // stateless; seals credentials to agent keys
    private final AgentRegistry registry;
    private final AccessGuard guard;
    private final TenantStore tenants;
    private final Optional<OperationLog> operationLog;
    private final Optional<JobGateway> jobGateway;
    private final Optional<FleetService> fleet;
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "gw-sse");
        thread.setDaemon(true);
        return thread;
    });

    public GatewayController(AgentRegistry registry, AccessGuard guard, TenantStore tenants,
                             Optional<OperationLog> operationLog, Optional<JobGateway> jobGateway,
                             Optional<FleetService> fleet) {
        this.registry = registry;
        this.guard = guard;
        this.tenants = tenants;
        this.operationLog = operationLog;
        this.jobGateway = jobGateway;
        this.fleet = fleet;
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
        DeviceRef ref = deviceRefFor(tenantId, host, req.port());
        sseExecutor.submit(() -> {
            try {
                EventSink sink = event -> sendSse(emitter, "event", codec.toJson(event));
                Result result = engine.get().execute(Command.Inventory.of(ref), sink);
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

    /** Apply raw HiveOS CLI lines to a device (the universal config escape hatch — hostname, radio, capwap,
     *  captive portal, anything the CLI supports). Operator-level; the result is secret-redacted by dispatch. */
    @PostMapping("/api/agents/{agentId}/apply-config")
    public Callable<ResponseEntity<String>> applyConfig(@PathVariable String agentId,
                                                        @RequestBody ApplyConfigRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> {
            List<String> commands = req.commands() == null ? List.of()
                    : req.commands().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (commands.isEmpty()) {
                return status(400, new ApiError("bad_request", "at least one CLI command is required"));
            }
            return dispatch(p, agentId, "apply-config", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.ApplyConfig.of(ref, commands, req.save()));
        };
    }

    /** Re-apply a captured running-config to a device (additive replay), optionally persisting it. The cloud
     *  sends the config text; the agent applies it with its local credentials. Operator-level; secret-redacted. */
    @PostMapping("/api/agents/{agentId}/restore")
    public Callable<ResponseEntity<String>> restore(@PathVariable String agentId, @RequestBody RestoreRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> {
            if (req == null || req.runningConfig() == null || req.runningConfig().isBlank()) {
                return status(400, new ApiError("bad_request", "runningConfig is required"));
            }
            return dispatch(p, agentId, "restore", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.RestoreConfig.of(ref, req.runningConfig(), req.save()));
        };
    }

    /** Upgrade a device's firmware from a reachable image URL, optionally rebooting to activate it. Operator-level.
     *  LAB/UNTESTED in v0.1 — the HiveOS upgrade path has not been validated against a live AP. */
    @PostMapping("/api/agents/{agentId}/firmware-upgrade")
    public Callable<ResponseEntity<String>> firmwareUpgrade(@PathVariable String agentId,
                                                            @RequestBody FirmwareUpgradeRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> {
            if (req == null || req.imageUrl() == null || req.imageUrl().isBlank()) {
                return status(400, new ApiError("bad_request", "imageUrl is required"));
            }
            return dispatch(p, agentId, "firmware-upgrade", new DeviceRequest(req.host(), req.port()),
                    ref -> Command.FirmwareUpgrade.of(ref, req.imageUrl().trim(), req.reboot()));
        };
    }

    /** Adopt a discovered host into the managed fleet: inventory it through the agent to learn its real
     *  serial/model, then register a device keyed by that serial, on the agent's site. Admin-level. */
    @PostMapping("/api/agents/{agentId}/adopt")
    public Callable<ResponseEntity<String>> adopt(@PathVariable String agentId, @RequestBody AdoptRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, agentScope(p, agentId));
        return () -> {
            if (fleet.isEmpty()) {
                return status(501, new ApiError("not_supported", "the fleet registry requires the 'postgres' profile"));
            }
            Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
            if (engine.isEmpty()) {
                return status(404, new ApiError("agent_not_connected", agentId));
            }
            if (req == null || req.host() == null || req.host().isBlank()) {
                return status(400, new ApiError("bad_request", "host is required"));
            }
            String host = req.host().trim();
            int port = req.port() == null ? 22 : req.port();
            try {
                Result result = engine.get().execute(Command.Inventory.of(DeviceRef.ssh(host, port)), EventSink.NOOP);
                if (!(result instanceof Result.Inventory inv)) {
                    return status(502, new ApiError("unexpected_result", "inventory did not return a device"));
                }
                Device device = inv.device();
                if (device.serial() == null || device.serial().isBlank()) {
                    return status(422, new ApiError("no_serial", "the device reported no serial; cannot adopt it"));
                }
                String label = req.label() == null || req.label().isBlank() ? device.serial() : req.label().trim();
                String credRef = req.credRef() == null || req.credRef().isBlank() ? null : req.credRef().trim();
                String siteId = tenants.agentSiteId(agentId).orElse(null);
                String deviceId = fleet.get().registerDevice(p.tenantId(), device.serial(), device.model(),
                        label, host, siteId, agentId, credRef);
                record(p.tenantId(), agentId, "adopt", host, device.serial());
                return json(new AdoptResponse(deviceId, device.serial(), device.model()));
            } catch (DataIntegrityViolationException e) {
                return status(409, new ApiError("conflict", "a device with that serial is already registered"));
            } catch (Exception e) {
                log.warn("adopt via agent '{}' failed: {}", agentId, e.getMessage());
                return status(502, new ApiError(e.getClass().getSimpleName(),
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        };
    }

    /** Set (or rotate) a device's SSH credential. The secret is sealed to the agent's public key HERE and
     *  forwarded over the (mTLS) agent channel; it is NEVER persisted in the cloud and never goes through the
     *  durable-job table. Admin-level on the agent's scope. When {@code alsoSetOnDevice} is set, the agent
     *  also changes the admin password on the AP itself. */
    @PostMapping("/api/agents/{agentId}/set-credential")
    public Callable<ResponseEntity<String>> setCredential(@PathVariable String agentId,
                                                          @RequestBody SetCredentialRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, agentScope(p, agentId));
        return () -> doSetCredential(p, agentId, req);
    }

    private ResponseEntity<String> doSetCredential(Principal p, String agentId, SetCredentialRequest req) {
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        if (req == null || req.host() == null || req.host().isBlank()) {
            return status(400, new ApiError("bad_request", "host is required"));
        }
        if (req.username() == null || req.username().isBlank()) {
            return status(400, new ApiError("bad_request", "username is required"));
        }
        String host = req.host().trim();
        int port = req.port() == null ? 22 : req.port();
        String deviceId = req.deviceId() == null || req.deviceId().isBlank() ? null : req.deviceId().trim();

        // Resolve the credRef: an explicit one, else the device's existing one, else mint a stable one.
        String credRef = req.credRef() != null && !req.credRef().isBlank() ? req.credRef().trim()
                : fleet.flatMap(f -> f.credRefForHost(p.tenantId(), host)).orElse(null);
        if (credRef == null) {
            credRef = deviceId != null ? deviceId : "cred-" + host;
        }
        // Pin the credRef on the registered device so future ops resolve it (best-effort).
        if (fleet.isPresent() && deviceId != null) {
            try {
                fleet.get().setCredRef(p.tenantId(), deviceId, credRef);
            } catch (Exception e) {
                log.warn("could not pin credRef on device '{}': {}", deviceId, e.getMessage());
            }
        }

        // Seal the secret to the agent's public key — the gateway keeps no plaintext. Without a key (a dev
        // agent over ws://) we fall back to the INSECURE plain1: form and say so loudly.
        PublicKey agentKey = registry.publicKey(p.tenantId(), agentId).orElse(null);
        if (agentKey == null) {
            log.warn("agent '{}' has no public key (no mTLS cert); sealing the credential with the INSECURE "
                    + "plain1: dev fallback — enable mTLS for end-to-end credential encryption", agentId);
        }
        String sealed = envelope.seal(agentKey, CredentialPayload.encode(req.username().trim(),
                req.password() == null ? "" : req.password()));

        try {
            DeviceRef ref = DeviceRef.ssh(host, port, credRef);
            Command cmd = Command.SetCredential.of(ref, credRef, sealed, req.alsoSetOnDevice());
            Result result = engine.get().execute(cmd, EventSink.NOOP);
            record(p.tenantId(), agentId, "set-credential", host, result.getClass().getSimpleName());
            if (result instanceof Result.CredentialSet set) {
                return json(new CredentialSetResponse(set.credRef(), set.vaultUpdated(), set.deviceUpdated()));
            }
            return status(502, new ApiError("unexpected_result", "set-credential did not return a credential result"));
        } catch (Exception e) {
            // The exception message is the engine's failure text; it never carries the secret.
            log.warn("set-credential via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** Bulk read op (backup|inventory) over every registered device in a group/site/org. Each device is
     *  reached through ITS agent with ITS credential reference; results are reported per device. Viewer-level
     *  on the target scope (reads only). */
    @PostMapping("/api/fleet/bulk/{op}")
    public Callable<ResponseEntity<String>> bulk(@PathVariable String op, @RequestBody BulkRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, bulkScope(p.tenantId(), req));
        return () -> doBulk(p, op, req);
    }

    private ResourceScope bulkScope(String tenantId, BulkRequest req) {
        if (req != null && req.groupId() != null && !req.groupId().isBlank()) {
            return fleet.flatMap(f -> f.groupScope(tenantId, req.groupId().trim())).orElseGet(ResourceScope::org);
        }
        if (req != null && req.siteId() != null && !req.siteId().isBlank()) {
            return ResourceScope.site(req.siteId().trim());
        }
        return ResourceScope.org();
    }

    private ResponseEntity<String> doBulk(Principal p, String op, BulkRequest req) {
        if (fleet.isEmpty()) {
            return status(501, new ApiError("not_supported", "the fleet registry requires the 'postgres' profile"));
        }
        if (!"backup".equals(op) && !"inventory".equals(op)) {
            return status(400, new ApiError("bad_request", "op must be backup or inventory"));
        }
        String siteId = req == null ? null : req.siteId();
        String groupId = req == null ? null : req.groupId();
        List<FleetService.Device> devices = fleet.get().devicesFor(p.tenantId(), siteId, groupId);
        List<BulkOutcome> results = new ArrayList<>(devices.size());
        int ok = 0;
        // The single scope-level check in bulk() is only sound when every resolved device is individually
        // covered by it. For a cross-site GROUP that is false (a device pinned to another site can be tagged
        // in), so re-authorize each device against its OWN lineage before touching it. And cap the loop to a
        // wall-clock budget under the MVC async timeout so a slow/large fleet returns the partial results
        // gathered so far instead of being discarded by a timeout.
        long deadline = System.nanoTime() + BULK_BUDGET.toNanos();
        for (int i = 0; i < devices.size(); i++) {
            FleetService.Device d = devices.get(i);
            if (System.nanoTime() > deadline) {
                for (FleetService.Device pending : devices.subList(i, devices.size())) {
                    results.add(new BulkOutcome(pending.deviceId(), pending.serial(), pending.mgmtIp(),
                            "timeout", "bulk time budget exceeded before this device was reached"));
                }
                break;
            }
            if (!guard.allows(p, Role.VIEWER, ResourceScope.device(d.siteId(), Set.copyOf(d.groups())))) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "forbidden",
                        "not authorized to view this device"));
                continue;
            }
            if (d.agentId() == null || d.mgmtIp() == null) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "skipped", "no agent or host"));
                continue;
            }
            Optional<RemoteEngine> engine = registry.engine(p.tenantId(), d.agentId());
            if (engine.isEmpty()) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "agent_offline", d.agentId()));
                continue;
            }
            try {
                // Use the device row's OWN credential reference — not a re-lookup by mgmt_ip, which is not
                // unique and could attach a sibling device's credRef on an IP collision.
                DeviceRef ref = DeviceRef.ssh(d.mgmtIp(), 22, d.credRef());
                Command cmd = "inventory".equals(op)
                        ? Command.Inventory.of(ref) : Command.BackupConfig.of(ref);
                Result result = engine.get().execute(cmd, EventSink.NOOP);
                record(p.tenantId(), d.agentId(), "bulk-" + op, d.mgmtIp(), result.getClass().getSimpleName());
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "ok", null));
                ok++;
            } catch (Exception e) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "failed",
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        // failed counts only true failures — skipped/agent_offline/forbidden/timeout devices were never
        // attempted, so folding them into 'failed' (total - ok) would over-report. The per-device list carries
        // their real status.
        int failed = (int) results.stream().filter(o -> "failed".equals(o.status())).count();
        return json(new BulkResponse(op, devices.size(), ok, failed, results));
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
            DeviceRef ref = deviceRefFor(p.tenantId(), req.host().trim(), req.port());
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

    /** Builds the {@link DeviceRef} for an op, attaching the registered device's credential REFERENCE (which
     *  the agent resolves locally to the secret) when the host is a registered device — otherwise a null
     *  credRef, and the agent falls back to its default. The cloud only ever sends the reference. */
    private DeviceRef deviceRefFor(String tenantId, String host, Integer portObj) {
        int port = portObj == null ? 22 : portObj;
        String credRef = fleet.flatMap(f -> f.credRefForHost(tenantId, host)).orElse(null);
        return DeviceRef.ssh(host, port, credRef);
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
