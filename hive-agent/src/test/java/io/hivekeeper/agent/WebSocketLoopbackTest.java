package io.hivekeeper.agent;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.protocol.AgentRuntime;
import io.hivekeeper.protocol.Frame;
import io.hivekeeper.wire.JsonCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end over a REAL localhost WebSocket: a stub gateway dispatches a Job, the agent runs it
 *  through a (stub) local engine and streams the result back. Exercises WebSocketFrameChannel +
 *  AgentRuntime + the JSON frame codec across an actual socket. */
class WebSocketLoopbackTest {

    @Test
    void agentReceivesJobOverWebSocketAndStreamsResultBack() throws Exception {
        int port = freePort();
        JsonCodec codec = new JsonCodec();
        BlockingQueue<Frame> serverReceived = new LinkedBlockingQueue<>();
        CountDownLatch serverReady = new CountDownLatch(1);

        WebSocketServer gateway = new WebSocketServer(new InetSocketAddress("127.0.0.1", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                Frame.Job job = new Frame.Job("job-1", "idem-1", System.currentTimeMillis() + 5_000,
                        Command.Inventory.of(DeviceRef.ssh("192.168.1.101")));
                conn.send(codec.toJson(job));
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                serverReceived.add(codec.fromJson(message, Frame.class));
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
            }

            @Override
            public void onStart() {
                serverReady.countDown();
            }
        };
        gateway.setReuseAddr(true);
        gateway.start();
        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "gateway should start");

        Engine stubEngine = (command, sink) -> {
            var deviceId = ((Command.Inventory) command).device().id();
            sink.emit(new Event.Progress(command.commandId(), deviceId, "working", 50));
            Device device = Device.builder().id(deviceId).model("AP230").build();
            return new Result.Inventory(command.commandId(), deviceId, device);
        };

        WebSocketFrameChannel channel = new WebSocketFrameChannel(URI.create("ws://127.0.0.1:" + port));
        AgentRuntime agent = new AgentRuntime(stubEngine, channel, "agent-test");
        agent.start();
        channel.onConnected(agent::announce);
        channel.start();

        try {
            boolean sawHello = false;
            boolean sawResult = false;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !sawResult) {
                Frame frame = serverReceived.poll(500, TimeUnit.MILLISECONDS);
                if (frame instanceof Frame.Hello) {
                    sawHello = true;
                } else if (frame instanceof Frame.JobResult jr) {
                    Result.Inventory inv = assertInstanceOf(Result.Inventory.class, jr.result());
                    assertEquals("AP230", inv.device().model());
                    sawResult = true;
                }
            }
            assertTrue(sawHello, "gateway should receive the agent's Hello");
            assertTrue(sawResult, "gateway should receive the JobResult");
        } finally {
            channel.close();
            agent.close();
            gateway.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
