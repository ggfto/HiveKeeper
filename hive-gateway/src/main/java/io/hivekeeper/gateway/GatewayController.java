package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;

/**
 * The control-plane REST API. It dispatches work to a connected agent through that agent's
 * {@link RemoteEngine} — the gateway sends only intent ({host}); the agent resolves credentials locally
 * and does the SSH. Note there is NO password in the request: that is the whole point of the topology.
 */
@RestController
@Slf4j
public class GatewayController {

    /** Cloud sends intent only — host (and optional port). Credentials live on the agent. */
    public record DeviceRequest(String host, Integer port) {
    }

    public record ApiError(String error, String detail) {
    }

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;

    public GatewayController(AgentRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/agents")
    public ResponseEntity<String> agents() {
        return json(registry.agentIds());
    }

    @PostMapping("/api/agents/{agentId}/inventory")
    public ResponseEntity<String> inventory(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        return dispatch(agentId, req, Command.Inventory::of);
    }

    @PostMapping("/api/agents/{agentId}/backup")
    public ResponseEntity<String> backup(@PathVariable String agentId, @RequestBody DeviceRequest req) {
        return dispatch(agentId, req, Command.BackupConfig::of);
    }

    private ResponseEntity<String> dispatch(String agentId, DeviceRequest req,
                                            java.util.function.Function<DeviceRef, Command> commandFactory) {
        Optional<RemoteEngine> engine = registry.engine(agentId);
        if (engine.isEmpty()) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(codec.toJson(new ApiError("agent_not_connected", agentId)));
        }
        if (req == null || req.host() == null || req.host().isBlank()) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(codec.toJson(new ApiError("bad_request", "host is required")));
        }
        try {
            DeviceRef ref = DeviceRef.ssh(req.host().trim(), req.port() == null ? 22 : req.port());
            Result result = engine.get().execute(commandFactory.apply(ref), EventSink.NOOP);
            return json(result);
        } catch (Exception e) {
            log.warn("dispatch to agent '{}' failed: {}", agentId, e.getMessage());
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                    .body(codec.toJson(new ApiError(e.getClass().getSimpleName(),
                            e.getMessage() == null ? "" : e.getMessage())));
        }
    }

    private ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(codec.toJson(value));
    }
}
