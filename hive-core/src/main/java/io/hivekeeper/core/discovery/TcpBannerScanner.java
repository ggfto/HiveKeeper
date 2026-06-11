package io.hivekeeper.core.discovery;

import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Unprivileged discovery: a concurrent TCP-connect sweep to a port (22 by default) that grabs the
 * service banner. This is the most reliable no-root primitive for these APs — port 22 is the one port
 * you know a standalone HiveOS AP exposes, and its {@code SSH-2.0-...} banner fingerprints it before
 * any authentication. Uses Java 21 virtual threads with a concurrency gate.
 */
@Slf4j
public final class TcpBannerScanner implements Scanner {

    private static final int MAX_BANNER_BYTES = 255;

    private final int maxConcurrency;

    public TcpBannerScanner() {
        this(128);
    }

    public TcpBannerScanner(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public List<DiscoveryResult> scan(List<String> hosts, int port, int timeoutMillis) {
        Semaphore gate = new Semaphore(maxConcurrency);
        List<DiscoveryResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DiscoveryResult>> futures = new ArrayList<>(hosts.size());
            for (String host : hosts) {
                futures.add(pool.submit(() -> {
                    gate.acquire();
                    try {
                        return probe(host, port, timeoutMillis);
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<DiscoveryResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    log.debug("probe task failed: {}", e.getMessage());
                }
            }
        }
        return results;
    }

    private static DiscoveryResult probe(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            String banner = readBanner(socket.getInputStream());
            boolean ssh = banner != null && banner.startsWith("SSH-");
            return new DiscoveryResult(host, port, true, banner, ssh);
        } catch (IOException e) {
            return new DiscoveryResult(host, port, false, null, false);
        }
    }

    private static String readBanner(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() >= MAX_BANNER_BYTES) {
                break;
            }
        }
        String banner = buf.toString(StandardCharsets.US_ASCII).strip();
        return banner.isEmpty() ? null : banner;
    }
}
