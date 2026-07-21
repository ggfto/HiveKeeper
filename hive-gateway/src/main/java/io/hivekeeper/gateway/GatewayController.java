package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.crypto.CredentialPayload;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.crypto.PskGenerator;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.backup.BackupDestinationService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.core.crypto.Secrets;
import io.hivekeeper.gateway.alerts.AlertService;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.ppsk.PpskUserService;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** Create or remove an SSID on a device behind the agent. {@code security} is the protocol suite
     *  (open|wpa2-aes-psk|wpa3-sae|wpa2-aes-8021x|wpa3-aes-8021x-std); a null/blank value defaults to WPA2-PSK.
     *  The enterprise (802.1X) suites carry the RADIUS fields instead of a {@code psk}. */
    public record SsidRequest(String host, Integer port, String name, String psk, Integer vlan, boolean remove,
                              String security, String radiusServer, String radiusSecret, Integer radiusAuthPort) {
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
                             String name, String psk, Integer vlan, boolean remove, String password,
                             String security, String radiusServer, String radiusSecret, Integer radiusAuthPort) {
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

    /** Mint a Private-PSK user (PPSK "Caminho B"). The gateway generates the key, seals it to the agent, and
     *  records only metadata + a reference — the usable PSK lives on the agent's RADIUS store. {@code pskLength}
     *  is optional (8–63, default 20). {@code userProfileAttr}/{@code vlanId} are the policy RADIUS returns. */
    public record CreatePpskUserRequest(String securityObject, String userGroup, String username,
                                        Integer userProfileAttr, Integer vlanId, String scheduleName,
                                        List<String> macBindings, Integer pskLength) {
    }

    /** A PPSK user as the cloud sees it — metadata only, NEVER a usable key. */
    public record PpskUserView(String id, String securityObject, String userGroup, String username,
                               Integer userProfileAttr, Integer vlanId, String scheduleName,
                               List<String> macBindings, String status, java.time.Instant createdAt,
                               java.time.Instant rotatedAt) {
    }

    /** Create/rotate response: the user plus the freshly generated PSK, returned ONCE for the operator to hand
     *  to the user. The cloud never stores it; this is the only time it is visible. */
    public record PpskUserCreated(PpskUserView user, String psk) {
    }

    public record PpskUserList(List<PpskUserView> users) {
    }

    public record AdoptResponse(String deviceId, String serial, String model, boolean baselineCaptured) {
    }

    /** Revoke an agent's enrollment (decommission/compromise). {@code reason} is optional, recorded for audit. */
    public record RevokeAgentRequest(String reason) {
    }

    /** Acknowledges an agent lifecycle action (revoke). */
    public record AgentActionResult(String agentId, String status) {
    }

    /** Re-enroll response: the agent and its fresh one-time enrollment token (returned ONCE). */
    public record ReEnrollResponse(String agentId, String token) {
    }

    /** Run a read op across every registered device in a group, a site, or (both null) the whole org. */
    public record BulkRequest(String siteId, String groupId) {
    }

    /** Apply the same CLI lines (a config template) to every registered device in a group, a site, or (both
     *  null) the whole org, optionally persisting with {@code save config}. */
    public record BulkApplyConfigRequest(String siteId, String groupId, List<String> commands, boolean save) {
    }

    public record BulkOutcome(String deviceId, String serial, String host, String status, String detail) {
    }

    public record BulkResponse(String op, int total, int ok, int failed, List<BulkOutcome> results) {
    }

    public record ApiError(String error, String detail) {
    }

    /** A notification channel as the UI sees it. */
    public record AlertChannelView(String id, String type, String target, String minSeverity, boolean enabled,
                                   java.time.Instant createdAt) {
    }

    /** Add a webhook or email channel. {@code minSeverity} (critical|warning|info) is the least-severe alert it
     *  receives; defaults to warning. */
    public record CreateChannelRequest(String type, String target, String minSeverity) {
    }

    public record ChannelEnabledRequest(boolean enabled) {
    }

    /** Per-tenant alert poller settings. */
    public record AlertSettingsView(int maxStations, boolean pollEnabled) {
    }

    /** A currently-firing alert, for the console's active-alerts view. */
    public record FiringAlertView(String deviceId, String agentId, String alertId, String severity, String message,
                                  java.time.Instant firstSeen, java.time.Instant lastSeen) {
    }

    public record AlertChannelList(List<AlertChannelView> channels) {
    }

    public record FiringAlertList(List<FiringAlertView> alerts) {
    }

    /** Wall-clock budget for a bulk op's device loop. Kept under the MVC async timeout (120s, see AsyncConfig)
     *  minus one worst-case agent round-trip (60s, see RemoteEngine) so the LAST device we start still finishes
     *  before Spring would abort the request and discard the partial BulkResponse. */
    private static final Duration BULK_BUDGET = Duration.ofSeconds(50);

    private final JsonCodec codec = new JsonCodec();
    private final EnvelopeCipher envelope = new EnvelopeCipher();   // stateless; seals credentials to agent keys
    private final PskGenerator pskGen = new PskGenerator();          // strong Private-PSK generation (PPSK)
    private final AgentRegistry registry;
    private final AccessGuard guard;
    private final TenantStore tenants;
    private final Optional<OperationLog> operationLog;
    private final Optional<JobGateway> jobGateway;
    private final Optional<FleetService> fleet;
    private final Optional<PpskUserService> ppskUsers;
    private final Optional<AlertService> alerts;
    private final BackupDestinationService backupDestinations;
    private final BackupDestinationProvisioner provisioner;
    private final SitePrimary sitePrimary;
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "gw-sse");
        thread.setDaemon(true);
        return thread;
    });

    public GatewayController(AgentRegistry registry, AccessGuard guard, TenantStore tenants,
                             Optional<OperationLog> operationLog, Optional<JobGateway> jobGateway,
                             Optional<FleetService> fleet, Optional<PpskUserService> ppskUsers,
                             Optional<AlertService> alerts, BackupDestinationService backupDestinations,
                             BackupDestinationProvisioner provisioner, SitePrimary sitePrimary) {
        this.registry = registry;
        this.guard = guard;
        this.tenants = tenants;
        this.operationLog = operationLog;
        this.jobGateway = jobGateway;
        this.fleet = fleet;
        this.ppskUsers = ppskUsers;
        this.alerts = alerts;
        this.backupDestinations = backupDestinations;
        this.provisioner = provisioner;
        this.sitePrimary = sitePrimary;
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
        String jobId = jobGateway.get().submit(p.tenantId(), agentId, req.type(),
                sealForJob(p, agentId, req.type(), command));
        return json(new JobSubmitted(jobId, "submitted"));
    }

    /** Seals a secret-bearing durable-job command (SSID passphrase / hive password) to the agent's public key
     *  so the persisted job row is unreadable by the gateway at rest — only the agent can unwrap it. Non-secret
     *  job types pass through unchanged. Without an agent key (dev, no mTLS) it falls back to the INSECURE
     *  plain1: form, logged loudly, mirroring the set-credential path. */
    private Command sealForJob(Principal p, String agentId, String type, Command command) {
        if (!"configure-ssid".equals(type) && !"configure-hive".equals(type)) {
            return command;
        }
        PublicKey agentKey = registry.publicKey(p.tenantId(), agentId).orElse(null);
        if (agentKey == null) {
            log.warn("agent '{}' has no public key (no mTLS cert); sealing the durable '{}' job with the INSECURE "
                    + "plain1: dev fallback — enable mTLS so job secrets are unreadable at rest", agentId, type);
        }
        String sealed = envelope.seal(agentKey, codec.toJson(command).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Command.Sealed.of(sealed);
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
            case "discover" -> Command.Discover.of(cidrOrNull(req), port, 800);
            case "configure-ssid" -> Command.ConfigureSsid.of(DeviceRef.ssh(requireHost(req), port),
                    req.remove() ? SsidSpec.removal(req.name())
                            : ssidSpec(req.name(), req.psk(), req.vlan(), req.security(),
                                    req.radiusServer(), req.radiusSecret(), req.radiusAuthPort()));
            case "configure-hive" -> Command.ConfigureHive.of(DeviceRef.ssh(requireHost(req), port),
                    new HiveSpec(req.name(), req.password(), null));
            default -> throw new IllegalArgumentException("unknown job type: " + req.type());
        };
    }

    /** Build a create {@link SsidSpec}, routing the enterprise (802.1X) suites to a RADIUS-backed spec and
     *  every other suite to the preshared-key/open path. Validation lives in {@code SsidSpec}. */
    private static SsidSpec ssidSpec(String name, String psk, Integer vlan, String security,
                                     String radiusServer, String radiusSecret, Integer radiusAuthPort) {
        if (security != null && SsidSpec.ENTERPRISE_SUITES.contains(security)) {
            return SsidSpec.createEnterprise(name, vlan, security,
                    new SsidSpec.RadiusSpec(radiusServer, radiusSecret, radiusAuthPort));
        }
        return SsidSpec.create(name, psk, vlan, security);
    }

    private static String requireHost(JobRequest req) {
        if (req.host() == null || req.host().isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        return req.host().trim();
    }

    /** A blank CIDR is allowed for discover — it becomes {@code null}, telling the agent to auto-detect its own
     *  subnet. Returns the trimmed value otherwise. */
    private static String cidrOrNull(JobRequest req) {
        return req.cidr() == null || req.cidr().isBlank() ? null : req.cidr().trim();
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
                spec = req.remove() ? SsidSpec.removal(req.name())
                        : ssidSpec(req.name(), req.psk(), req.vlan(), req.security(),
                                req.radiusServer(), req.radiusSecret(), req.radiusAuthPort());
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

                // Capture the AP's CURRENT config as the "as-adopted" baseline, so an AP a previous admin
                // set up has a git snapshot to diff and roll back to BEFORE anyone changes anything through
                // HiveKeeper. Best-effort: adoption already succeeded, so a failed capture is reported, not
                // fatal — the operator can always back up manually.
                boolean baselineCaptured = captureBaseline(engine.get(), host, port, credRef, agentId);

                return json(new AdoptResponse(deviceId, device.serial(), device.model(), baselineCaptured));
            } catch (DataIntegrityViolationException e) {
                return status(409, new ApiError("conflict", "a device with that serial is already registered"));
            } catch (Exception e) {
                log.warn("adopt via agent '{}' failed: {}", agentId, e.getMessage());
                return status(502, new ApiError(e.getClass().getSimpleName(),
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        };
    }

    /** Captures the running-config into the agent's git store as the adoption baseline. Never throws — a
     *  failed baseline must not undo an otherwise-successful adoption. */
    private boolean captureBaseline(RemoteEngine engine, String host, int port, String credRef, String agentId) {
        try {
            engine.execute(Command.BackupConfig.of(DeviceRef.ssh(host, port, credRef)), EventSink.NOOP);
            return true;
        } catch (Exception e) {
            log.warn("baseline capture failed for a device adopted via '{}': {} (adoption stands; back up "
                    + "manually)", agentId, e.getMessage());
            return false;
        }
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

    // -- backup destination: where the org's config history is pushed ------------------------------------

    /** What the browser sends. The token is write-only: it goes in, and it never comes back out. */
    record BackupDestinationRequest(String repoUrl, String branch, String username, String token) {
    }

    /** What the browser gets back. Deliberately has no token field at all, so none can leak by accident. */
    record BackupDestinationResponse(String repoUrl, String branch, String username, String updatedAt,
                                     String updatedBy, boolean configured, List<AgentDelivery> agents) {
    }

    /** Whether each agent in the org actually received the destination. */
    record AgentDelivery(String agentId, boolean delivered, String error) {
    }

    @GetMapping("/api/backup-destination")
    public ResponseEntity<String> getBackupDestination() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        return backupDestinations.get(p.tenantId())
                .map(d -> json(new BackupDestinationResponse(d.repoUrl(), d.branch(), d.username(),
                        d.updatedAt() == null ? null : d.updatedAt().toString(), d.updatedBy(), true, List.of())))
                .orElseGet(() -> json(new BackupDestinationResponse(null, null, null, null, null, false, List.of())));
    }

    /**
     * Sets the organization's backup destination and pushes it to every connected agent.
     *
     * <p>The token is stored encrypted so an agent that is offline right now — or enrolled next month — can
     * still be given it later without an operator re-typing anything. It is sealed separately to each agent's
     * own public key on the way out, so it is never in the clear on the wire.
     *
     * <p>Delivery is reported per agent rather than folded into one pass/fail: a fleet where two of three
     * agents took the change is a real state, and hiding it behind a single error would leave the operator
     * unable to tell which site is still writing nowhere.
     */
    @PostMapping("/api/backup-destination")
    public ResponseEntity<String> setBackupDestination(@RequestBody BackupDestinationRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());

        if (req.repoUrl() == null || req.repoUrl().isBlank()) {
            return status(400, new ApiError("repo_url_required", "a repository URL is required"));
        }
        if (req.token() == null || req.token().isBlank()) {
            return status(400, new ApiError("token_required", "a token is required to push to the repository"));
        }
        String branch = req.branch() == null || req.branch().isBlank() ? "main" : req.branch().trim();
        String username = req.username() == null || req.username().isBlank()
                ? "hivekeeper" : req.username().trim();

        backupDestinations.save(p.tenantId(), req.repoUrl().trim(), branch, username, req.token(), p.userId());
        record(p.tenantId(), null, "set-backup-destination", req.repoUrl().trim(), "ok");

        List<AgentDelivery> delivered = provisioner.deliverToAll(p.tenantId()).stream()
                .map(d -> new AgentDelivery(d.agentId(), d.delivered(), d.error()))
                .toList();
        return json(new BackupDestinationResponse(req.repoUrl().trim(), branch, username, null, p.userId(),
                true, delivered));
    }

    /** Clears the destination. Agents keep committing locally; they just stop pushing. */
    @DeleteMapping("/api/backup-destination")
    public ResponseEntity<String> clearBackupDestination() {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        backupDestinations.clear(p.tenantId());
        record(p.tenantId(), null, "clear-backup-destination", null, "ok");
        List<AgentDelivery> delivered = provisioner.deliverToAll(p.tenantId()).stream()
                .map(d -> new AgentDelivery(d.agentId(), d.delivered(), d.error()))
                .toList();
        return json(new BackupDestinationResponse(null, null, null, null, null, false, delivered));
    }

    // -- agent certificate lifecycle: revoke / re-enroll (slice 2 of automated enrollment) -----------------

    /** Revoke an agent's enrollment: the gateway then refuses its mTLS handshake AND its certificate renewals,
     *  so a compromised or decommissioned agent is locked out. Admin-level on the agent's scope. The agent's
     *  existing leaf cert is not magically un-trusted by TLS, but it can no longer connect or renew, and it
     *  expires on its own (90 days). Use {@code re-enroll} to bring a replacement back online. */
    @PostMapping("/api/agents/{agentId}/revoke")
    public ResponseEntity<String> revokeAgent(@PathVariable String agentId,
                                              @RequestBody(required = false) RevokeAgentRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, agentScope(p, agentId));
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        try {
            if (!tenants.revokeAgent(p.tenantId(), agentId, reason)) {
                return status(404, new ApiError("agent_not_found", agentId));
            }
            record(p.tenantId(), agentId, "revoke", null, reason == null ? "revoked" : reason);
            return json(new AgentActionResult(agentId, "revoked"));
        } catch (UnsupportedOperationException e) {
            return status(501, new ApiError("not_supported", e.getMessage()));
        }
    }

    /** Re-enroll an agent: clear its revoked/consumed marks and mint a FRESH one-time enrollment token, returned
     *  once for the operator to configure a replacement agent (which bootstraps a new certificate). Admin-level. */
    @PostMapping("/api/agents/{agentId}/re-enroll")
    public ResponseEntity<String> reEnrollAgent(@PathVariable String agentId) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, agentScope(p, agentId));
        try {
            Optional<String> token = tenants.reEnrollAgent(p.tenantId(), agentId);
            if (token.isEmpty()) {
                return status(404, new ApiError("agent_not_found", agentId));
            }
            record(p.tenantId(), agentId, "re-enroll", null, "new enrollment token issued");
            return json(new ReEnrollResponse(agentId, token.get()));
        } catch (UnsupportedOperationException e) {
            return status(501, new ApiError("not_supported", e.getMessage()));
        }
    }

    // -- PPSK users (Caminho B): admin-minted Private PSKs, owned on-prem by the agent's RADIUS store -------

    /** List an agent's PPSK users (metadata only — never a usable key). Viewer on the agent's site. */
    @GetMapping("/api/agents/{agentId}/ppsk-users")
    public ResponseEntity<String> listPpskUsers(@PathVariable String agentId) {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, agentScope(p, agentId));
        if (ppskUsers.isEmpty()) {
            return status(501, new ApiError("not_supported", "PPSK user management is not configured"));
        }
        List<PpskUserView> views = ppskUsers.get().list(p.tenantId(), agentId).stream().map(this::toView).toList();
        return json(new PpskUserList(views));
    }

    /** Mint a PPSK user: generate the key, seal it to the agent, provision its RADIUS store, record metadata.
     *  Operator on the agent's site. The freshly generated PSK is returned ONCE in the response. */
    @PostMapping("/api/agents/{agentId}/ppsk-users")
    public Callable<ResponseEntity<String>> createPpskUser(@PathVariable String agentId,
                                                           @RequestBody CreatePpskUserRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> doCreatePpskUser(p, agentId, req);
    }

    /** Rotate a PPSK user's key. Operator on the agent's site. Returns the new PSK ONCE. */
    @PostMapping("/api/agents/{agentId}/ppsk-users/{id}/rotate")
    public Callable<ResponseEntity<String>> rotatePpskUser(@PathVariable String agentId, @PathVariable String id) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> doRotatePpskUser(p, agentId, id);
    }

    /** Revoke a PPSK user: remove it from the agent's RADIUS store and mark it revoked. Operator on the site. */
    @DeleteMapping("/api/agents/{agentId}/ppsk-users/{id}")
    public Callable<ResponseEntity<String>> revokePpskUser(@PathVariable String agentId, @PathVariable String id) {
        Principal p = guard.authenticate();
        guard.require(p, Role.OPERATOR, agentScope(p, agentId));
        return () -> doRevokePpskUser(p, agentId, id);
    }

    private ResponseEntity<String> doCreatePpskUser(Principal p, String agentId, CreatePpskUserRequest req) {
        if (ppskUsers.isEmpty()) {
            return status(501, new ApiError("not_supported", "PPSK user management is not configured"));
        }
        if (req == null || req.securityObject() == null || req.securityObject().isBlank()
                || req.username() == null || req.username().isBlank()) {
            return status(400, new ApiError("bad_request", "securityObject and username are required"));
        }
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        String so = req.securityObject().trim();
        String username = req.username().trim();
        String psk;
        try {
            psk = pskGen.generate(req.pskLength() == null ? 20 : req.pskLength());
        } catch (IllegalArgumentException e) {
            return status(400, new ApiError("bad_request", e.getMessage()));
        }
        List<String> macs = req.macBindings() == null ? List.of() : req.macBindings();
        String sealed = sealPsk(p, agentId, username, psk);
        Command cmd = Command.ManagePpskUser.create(so, req.userGroup(), username, sealed,
                req.userProfileAttr(), req.vlanId(), req.scheduleName(), macs);
        try {
            Result result = engine.get().execute(cmd, EventSink.NOOP);
            if (!(result instanceof Result.PpskUserManaged)) {
                return status(502, new ApiError("unexpected_result", "create did not return a PPSK result"));
            }
            String pskRef = "pskref-" + java.util.UUID.randomUUID();
            String id = ppskUsers.get().create(p.tenantId(), agentId, so, req.userGroup(), username, pskRef,
                    req.userProfileAttr(), req.vlanId(), req.scheduleName(), macs);
            record(p.tenantId(), agentId, "ppsk-create", so, "PpskUserManaged");
            PpskUserView view = ppskUsers.get().get(p.tenantId(), id).map(this::toView).orElse(null);
            return json(new PpskUserCreated(view, psk));
        } catch (IllegalStateException e) {
            return status(409, new ApiError("conflict", e.getMessage()));
        } catch (Exception e) {
            log.warn("ppsk create via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private ResponseEntity<String> doRotatePpskUser(Principal p, String agentId, String id) {
        if (ppskUsers.isEmpty()) {
            return status(501, new ApiError("not_supported", "PPSK user management is not configured"));
        }
        Optional<PpskUserService.PpskUser> existing = ppskUsers.get().get(p.tenantId(), id);
        if (existing.isEmpty() || !existing.get().agentId().equals(agentId)) {
            return status(404, new ApiError("ppsk_user_not_found", id));
        }
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        PpskUserService.PpskUser u = existing.get();
        String psk = pskGen.generate(20);
        String sealed = sealPsk(p, agentId, u.username(), psk);
        Command cmd = Command.ManagePpskUser.rotate(u.securityObject(), u.userGroup(), u.username(), sealed,
                u.userProfileAttr(), u.vlanId(), u.scheduleName(), u.macBindings());
        try {
            Result result = engine.get().execute(cmd, EventSink.NOOP);
            if (!(result instanceof Result.PpskUserManaged)) {
                return status(502, new ApiError("unexpected_result", "rotate did not return a PPSK result"));
            }
            ppskUsers.get().markRotated(p.tenantId(), id, "pskref-" + java.util.UUID.randomUUID());
            record(p.tenantId(), agentId, "ppsk-rotate", u.securityObject(), "PpskUserManaged");
            PpskUserView view = ppskUsers.get().get(p.tenantId(), id).map(this::toView).orElse(null);
            return json(new PpskUserCreated(view, psk));
        } catch (Exception e) {
            log.warn("ppsk rotate via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private ResponseEntity<String> doRevokePpskUser(Principal p, String agentId, String id) {
        if (ppskUsers.isEmpty()) {
            return status(501, new ApiError("not_supported", "PPSK user management is not configured"));
        }
        Optional<PpskUserService.PpskUser> existing = ppskUsers.get().get(p.tenantId(), id);
        if (existing.isEmpty() || !existing.get().agentId().equals(agentId)) {
            return status(404, new ApiError("ppsk_user_not_found", id));
        }
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        PpskUserService.PpskUser u = existing.get();
        Command cmd = Command.ManagePpskUser.revoke(u.securityObject(), u.username());
        try {
            engine.get().execute(cmd, EventSink.NOOP);
            ppskUsers.get().revoke(p.tenantId(), id);
            record(p.tenantId(), agentId, "ppsk-revoke", u.securityObject(), "PpskUserManaged");
            return json(toView(ppskUsers.get().get(p.tenantId(), id).orElse(u)));
        } catch (Exception e) {
            log.warn("ppsk revoke via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** Seals {@code username\npsk} to the agent's public key (env1:), or the INSECURE plain1: dev fallback when
     *  the agent connected without an mTLS cert — the gateway keeps no plaintext key either way. */
    private String sealPsk(Principal p, String agentId, String username, String psk) {
        PublicKey agentKey = registry.publicKey(p.tenantId(), agentId).orElse(null);
        if (agentKey == null) {
            log.warn("agent '{}' has no public key (no mTLS cert); sealing the PSK with the INSECURE plain1: "
                    + "dev fallback — enable mTLS for end-to-end key encryption", agentId);
        }
        return envelope.seal(agentKey, CredentialPayload.encode(username, psk));
    }

    private PpskUserView toView(PpskUserService.PpskUser u) {
        return new PpskUserView(u.id(), u.securityObject(), u.userGroup(), u.username(), u.userProfileAttr(),
                u.vlanId(), u.scheduleName(), u.macBindings(), u.status(), u.createdAt(), u.rotatedAt());
    }

    // -- Fleet alerting: notification channels, thresholds, and the current firing set (org-scoped) ---------

    private static final Set<String> SEVERITIES = Set.of("critical", "warning", "info");

    /** Read alert poller settings (thresholds + on/off). Viewer on the org. */
    @GetMapping("/api/alerts/settings")
    public ResponseEntity<String> alertSettings() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        AlertService.Settings s = alerts.get().settings(p.tenantId());
        return json(new AlertSettingsView(s.maxStations(), s.pollEnabled()));
    }

    /** Update alert poller settings. Admin on the org. */
    @PostMapping("/api/alerts/settings")
    public ResponseEntity<String> saveAlertSettings(@RequestBody AlertSettingsView req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        if (req == null || req.maxStations() < 1) {
            return status(400, new ApiError("bad_request", "maxStations must be >= 1"));
        }
        alerts.get().saveSettings(p.tenantId(), req.maxStations(), req.pollEnabled());
        AlertService.Settings s = alerts.get().settings(p.tenantId());
        return json(new AlertSettingsView(s.maxStations(), s.pollEnabled()));
    }

    /** List notification channels. Viewer on the org. */
    @GetMapping("/api/alerts/channels")
    public ResponseEntity<String> listAlertChannels() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        List<AlertChannelView> views = alerts.get().channels(p.tenantId()).stream().map(this::toChannelView).toList();
        return json(new AlertChannelList(views));
    }

    /** Add a webhook or email channel. Admin on the org. */
    @PostMapping("/api/alerts/channels")
    public ResponseEntity<String> addAlertChannel(@RequestBody CreateChannelRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        if (req == null || req.type() == null || !("webhook".equals(req.type()) || "email".equals(req.type()))) {
            return status(400, new ApiError("bad_request", "type must be webhook or email"));
        }
        if (req.target() == null || req.target().isBlank()) {
            return status(400, new ApiError("bad_request", "target is required"));
        }
        String minSeverity = req.minSeverity() == null || req.minSeverity().isBlank() ? "warning"
                : req.minSeverity().trim();
        if (!SEVERITIES.contains(minSeverity)) {
            return status(400, new ApiError("bad_request", "minSeverity must be critical, warning or info"));
        }
        try {
            String id = alerts.get().addChannel(p.tenantId(), req.type(), req.target().trim(), minSeverity);
            return alerts.get().channels(p.tenantId()).stream().filter(c -> c.id().equals(id)).findFirst()
                    .map(c -> json(toChannelView(c)))
                    .orElseGet(() -> status(500, new ApiError("error", "channel not found after create")));
        } catch (org.springframework.dao.DataAccessException e) {
            return status(409, new ApiError("conflict", "a channel for that target already exists"));
        }
    }

    /** Enable/disable a channel. Admin on the org. */
    @PostMapping("/api/alerts/channels/{id}")
    public ResponseEntity<String> setAlertChannelEnabled(@PathVariable String id,
                                                         @RequestBody ChannelEnabledRequest req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        alerts.get().setChannelEnabled(p.tenantId(), id, req != null && req.enabled());
        return json(new ApiError("ok", id));
    }

    /** Remove a channel. Admin on the org. */
    @DeleteMapping("/api/alerts/channels/{id}")
    public ResponseEntity<String> removeAlertChannel(@PathVariable String id) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        alerts.get().removeChannel(p.tenantId(), id);
        return json(new ApiError("ok", id));
    }

    /** The currently-firing alerts the poller is tracking. Viewer on the org. */
    @GetMapping("/api/alerts/firing")
    public ResponseEntity<String> firingAlerts() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        if (alerts.isEmpty()) {
            return status(501, new ApiError("not_supported", "alerting is not configured"));
        }
        List<FiringAlertView> views = alerts.get().firing(p.tenantId()).stream()
                .map(a -> new FiringAlertView(a.deviceId(), a.agentId(), a.alertId(), a.severity(), a.message(),
                        a.firstSeen(), a.lastSeen()))
                .toList();
        return json(new FiringAlertList(views));
    }

    private AlertChannelView toChannelView(AlertService.Channel c) {
        return new AlertChannelView(c.id(), c.type(), c.target(), c.minSeverity(), c.enabled(), c.createdAt());
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

    /** Bulk WRITE: apply the same HiveOS CLI lines (a config template) to every registered device in a
     *  group/site/org. Each device is reached through ITS agent with ITS credential reference; results are
     *  reported per device. Operator-level on the target scope (a write), then EACH device is re-authorized
     *  against its OWN lineage before it is touched. */
    @PostMapping("/api/fleet/bulk/apply-config")
    public Callable<ResponseEntity<String>> bulkApplyConfig(@RequestBody BulkApplyConfigRequest req) {
        Principal p = guard.authenticate();
        String siteId = req == null ? null : req.siteId();
        String groupId = req == null ? null : req.groupId();
        guard.require(p, Role.OPERATOR, bulkScope(p.tenantId(), siteId, groupId));
        return () -> doBulkApplyConfig(p, req);
    }

    private ResourceScope bulkScope(String tenantId, BulkRequest req) {
        return bulkScope(tenantId, req == null ? null : req.siteId(), req == null ? null : req.groupId());
    }

    private ResourceScope bulkScope(String tenantId, String siteId, String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            return fleet.flatMap(f -> f.groupScope(tenantId, groupId.trim())).orElseGet(ResourceScope::org);
        }
        if (siteId != null && !siteId.isBlank()) {
            return ResourceScope.site(siteId.trim());
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
            // Run on the site's current primary — so a second agent covers the site when the pinned one is
            // down, and exactly one agent runs each device's task rather than every agent on the site.
            String agent = sitePrimary.servingAgent(p.tenantId(), d.siteId(), d.agentId());
            Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agent);
            if (engine.isEmpty()) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "agent_offline", agent));
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

    private ResponseEntity<String> doBulkApplyConfig(Principal p, BulkApplyConfigRequest req) {
        if (fleet.isEmpty()) {
            return status(501, new ApiError("not_supported", "the fleet registry requires the 'postgres' profile"));
        }
        // Validate + normalize server-side (same as the single-device apply-config): strip blanks, require >=1.
        List<String> commands = (req == null || req.commands() == null) ? List.of()
                : req.commands().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (commands.isEmpty()) {
            return status(400, new ApiError("bad_request", "at least one CLI command is required"));
        }
        String siteId = req.siteId();
        String groupId = req.groupId();
        boolean save = req.save();
        List<FleetService.Device> devices = fleet.get().devicesFor(p.tenantId(), siteId, groupId);
        List<BulkOutcome> results = new ArrayList<>(devices.size());
        int ok = 0;
        // Re-authorize each device against its OWN lineage at OPERATOR (a write) — a cross-site group can carry a
        // device pinned to another site — and cap the fan-out to the same wall-clock budget as the read path so a
        // slow/large fleet returns the partial results gathered so far rather than being discarded by a timeout.
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
            if (!guard.allows(p, Role.OPERATOR, ResourceScope.device(d.siteId(), Set.copyOf(d.groups())))) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "forbidden",
                        "not authorized to configure this device"));
                continue;
            }
            if (d.agentId() == null || d.mgmtIp() == null) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "skipped", "no agent or host"));
                continue;
            }
            // Run on the site's current primary — so a second agent covers the site when the pinned one is
            // down, and exactly one agent runs each device's task rather than every agent on the site.
            String agent = sitePrimary.servingAgent(p.tenantId(), d.siteId(), d.agentId());
            Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agent);
            if (engine.isEmpty()) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "agent_offline", agent));
                continue;
            }
            try {
                // The device row's OWN credential reference — never a re-lookup by mgmt_ip (not unique).
                DeviceRef ref = DeviceRef.ssh(d.mgmtIp(), 22, d.credRef());
                Result result = engine.get().execute(Command.ApplyConfig.of(ref, commands, save), EventSink.NOOP);
                record(p.tenantId(), d.agentId(), "bulk-apply-config", d.mgmtIp(), result.getClass().getSimpleName());
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "ok", null));
                ok++;
            } catch (Exception e) {
                results.add(new BulkOutcome(d.deviceId(), d.serial(), d.mgmtIp(), "failed",
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        int failed = (int) results.stream().filter(o -> "failed".equals(o.status())).count();
        return json(new BulkResponse("apply-config", devices.size(), ok, failed, results));
    }

    private ResponseEntity<String> doDiscover(Principal p, String agentId, DiscoverRequest req) {
        Optional<RemoteEngine> engine = registry.engine(p.tenantId(), agentId);
        if (engine.isEmpty()) {
            return status(404, new ApiError("agent_not_connected", agentId));
        }
        // A blank CIDR is allowed and means "auto-detect": the agent scans its own primary subnet (it is the node
        // on the AP LAN). Only a non-blank value is validated/trimmed here; null flows to Command.Discover, which
        // the agent resolves via its local interfaces.
        String cidr = (req == null || req.cidr() == null || req.cidr().isBlank()) ? null : req.cidr().trim();
        int port = req != null && req.port() != null ? req.port() : 22;
        int timeout = req != null && req.timeoutMillis() != null ? req.timeoutMillis() : 800;
        try {
            Command cmd = Command.Discover.of(cidr, port, timeout);
            Result result = engine.get().execute(cmd, EventSink.NOOP);
            record(p.tenantId(), agentId, "discover", cidr == null ? "auto" : cidr, result.getClass().getSimpleName());
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
