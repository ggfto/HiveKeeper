package io.hivekeeper.server;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.discovery.Subnets;
import io.hivekeeper.core.model.DiscoveryResult;
import io.hivekeeper.core.discovery.TcpBannerScanner;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.tasks.storage.GitBackupStore;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP face of the engine. Each endpoint maps a request to a {@code Command}, runs it through a
 * {@link HiveCore#localEngine}, and serializes the {@code Result}/{@code Event} with the shared
 * {@link JsonCodec}. Results are NOT hand-mapped, so new model fields flow through automatically.
 */
@RestController
@Slf4j
public class ApiController {

    private final JsonCodec codec = new JsonCodec();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** What I/O type this represents — a backup store error wrapped in a record for JSON. */
    public record ApiError(String error, String detail) {
    }

    @PostMapping("/api/inventory")
    public ResponseEntity<String> inventory(@RequestBody ConnectionRequest req) {
        try {
            DeviceRef ref = ServerSupport.deviceRef(req);
            Engine engine = HiveCore.localEngine(ServerSupport.credentials(req), ServerSupport.NO_OP_STORE);
            return json(engine.execute(Command.Inventory.of(ref), EventSink.NOOP));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/backup")
    public ResponseEntity<String> backup(@RequestBody ConnectionRequest req) {
        try {
            DeviceRef ref = ServerSupport.deviceRef(req);
            BackupStore store = new GitBackupStore(Path.of(ServerSupport.backupDir(req)));
            Engine engine = HiveCore.localEngine(ServerSupport.credentials(req), store);
            return json(engine.execute(Command.BackupConfig.of(ref), EventSink.NOOP));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/api/discover")
    public ResponseEntity<String> discover(@RequestParam(required = false) String cidr,
                                           @RequestParam(defaultValue = "22") int port,
                                           @RequestParam(name = "timeout", defaultValue = "800") int timeoutMillis) {
        String range = cidr == null || cidr.isBlank() ? Subnets.localIpv4Cidr() : cidr;
        if (range == null) {
            return error(new IllegalArgumentException("could not determine local subnet; pass ?cidr="));
        }
        try {
            List<DiscoveryResult> results = new TcpBannerScanner()
                    .scan(Subnets.hostsForCidr(range), port, timeoutMillis).stream()
                    .filter(DiscoveryResult::reachable)
                    .sorted(Comparator.comparingLong(r -> Subnets.ipToLong(r.host())))
                    .toList();
            return json(results);
        } catch (IllegalArgumentException e) {
            return error(e);
        }
    }

    @PostMapping("/api/inventory/stream")
    public SseEmitter inventoryStream(@RequestBody ConnectionRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                DeviceRef ref = ServerSupport.deviceRef(req);
                Engine engine = HiveCore.localEngine(ServerSupport.credentials(req), ServerSupport.NO_OP_STORE);
                EventSink sink = event -> sendQuietly(emitter, "event", codec.toJson(event));
                Result result = engine.execute(Command.Inventory.of(ref), sink);
                sendQuietly(emitter, "result", codec.toJson(result));
                emitter.complete();
            } catch (Exception e) {
                log.warn("inventory stream failed: {}", e.getMessage());
                sendQuietly(emitter, "error", codec.toJson(new ApiError(e.getClass().getSimpleName(),
                        e.getMessage() == null ? "" : e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(codec.toJson(value));
    }

    private ResponseEntity<String> error(Exception e) {
        String detail = e.getMessage() == null ? "" : e.getMessage();
        return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                .body(codec.toJson(new ApiError(e.getClass().getSimpleName(), detail)));
    }

    private void sendQuietly(SseEmitter emitter, String name, String jsonData) {
        try {
            emitter.send(SseEmitter.event().name(name).data(jsonData, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("sse send failed: {}", e.getMessage());
        }
    }
}
