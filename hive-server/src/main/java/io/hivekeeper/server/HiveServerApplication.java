package io.hivekeeper.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HiveKeeper local server (deployment mode B): a thin Spring Boot adapter over the framework-free
 * engine, bound to localhost. It exposes the same {@code Command}/{@code Result}/{@code Event} contract
 * over HTTP/SSE that the CLI drives in-process — no logic lives here that isn't pure adaptation.
 */
@SpringBootApplication
public class HiveServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HiveServerApplication.class, args);
    }
}
