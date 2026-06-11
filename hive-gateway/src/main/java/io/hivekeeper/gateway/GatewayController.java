package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.gateway.tenant.Tenant;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;
import java.util.concurrent.Callable;
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

    public record ApiError(String error, String detail) {
    }

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;
    private final TenantStore tenants;

    public GatewayController(AgentRegistry registry, TenantStore tenants) {
        this.registry = registry;
        this.tenants = tenants;
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
        return () -> dispatch(apiKey, agentId, req, Command.Inventory::of);
    }

    @PostMapping("/api/agents/{agentId}/backup")
    public Callable<ResponseEntity<String>> backup(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DeviceRequest req) {
        return () -> dispatch(apiKey, agentId, req, Command.BackupConfig::of);
    }

    @PostMapping("/api/agents/{agentId}/discover")
    public Callable<ResponseEntity<String>> discover(
            @RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
            @PathVariable String agentId, @RequestBody DiscoverRequest req) {
        return () -> doDiscover(apiKey, agentId, req);
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
            return json(engine.get().execute(cmd, EventSink.NOOP));
        } catch (Exception e) {
            log.warn("discover via agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private ResponseEntity<String> dispatch(String apiKey, String agentId, DeviceRequest req,
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
            return json(result);
        } catch (Exception e) {
            log.warn("dispatch to agent '{}' failed: {}", agentId, e.getMessage());
            return status(502, new ApiError(e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage()));
        }
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
