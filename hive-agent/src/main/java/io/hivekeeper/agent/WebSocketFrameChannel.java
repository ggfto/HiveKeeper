package io.hivekeeper.agent;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A {@link FrameChannel} over an outbound WebSocket — the agent's real uplink to the gateway. Each WS
 * text message is one JSON-encoded {@link Frame}. It reconnects automatically with exponential backoff
 * + jitter and re-runs an {@code onConnected} hook after each (re)connect (so the agent re-announces).
 * Frames sent while disconnected are dropped (the gateway's job DB + agent resume cover redelivery).
 */
@Slf4j
public final class WebSocketFrameChannel implements FrameChannel {

    private final URI gatewayUri;
    private final JsonCodec codec = new JsonCodec();
    private final ScheduledExecutorService reconnector =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final SSLContext sslContext;
    private volatile Consumer<Frame> handler = frame -> { };
    private volatile Runnable onConnected = () -> { };
    private volatile InnerClient client;
    private volatile boolean running = true;
    private int attempt = 0;

    public WebSocketFrameChannel(URI gatewayUri) {
        this(gatewayUri, null);
    }

    /** @param sslContext mTLS context for {@code wss://} (client cert + CA trust); {@code null} for plain ws. */
    public WebSocketFrameChannel(URI gatewayUri, SSLContext sslContext) {
        this.gatewayUri = gatewayUri;
        this.sslContext = sslContext;
    }

    /** Hook invoked after each successful (re)connect — typically {@code agentRuntime::announce}. */
    public void onConnected(Runnable hook) {
        this.onConnected = hook;
    }

    public void start() {
        connect();
    }

    private void connect() {
        if (!running) {
            return;
        }
        log.info("connecting to gateway {}", gatewayUri);
        InnerClient c = new InnerClient(gatewayUri);
        if (sslContext != null) {
            c.setSocketFactory(sslContext.getSocketFactory());
        }
        this.client = c;
        c.connect();
    }

    @Override
    public void send(Frame frame) {
        InnerClient c = client;
        if (c != null && c.isOpen()) {
            try {
                c.send(codec.toJson(frame));
            } catch (Exception e) {
                log.debug("send failed: {}", e.getMessage());
            }
        } else {
            log.debug("dropping {} — not connected", frame.getClass().getSimpleName());
        }
    }

    @Override
    public void onFrame(Consumer<Frame> handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        running = false;
        reconnector.shutdownNow();
        InnerClient c = client;
        if (c != null) {
            c.close();
        }
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        long delay = backoffMillis(++attempt);
        log.info("reconnecting in {} ms (attempt {})", delay, attempt);
        try {
            reconnector.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // executor shut down (closing) — stop trying
        }
    }

    private static long backoffMillis(int attempt) {
        long base = (long) Math.min(30_000d, 500d * Math.pow(2, Math.min(attempt, 6)));
        long jitter = (long) (base * 0.2 * Math.random());
        return base + jitter;
    }

    private final class InnerClient extends WebSocketClient {

        InnerClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            attempt = 0;
            log.info("connected to gateway");
            onConnected.run();
        }

        @Override
        public void onMessage(String message) {
            try {
                handler.accept(codec.fromJson(message, Frame.class));
            } catch (Exception e) {
                log.warn("ignoring malformed frame: {}", e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.info("disconnected (code={}, remote={})", code, remote);
            scheduleReconnect();
        }

        @Override
        public void onError(Exception ex) {
            log.debug("ws error: {}", ex.getMessage());
        }
    }
}
