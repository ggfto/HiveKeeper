package io.hivekeeper.core.drivers;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/** Discovers {@link Driver} implementations via {@link ServiceLoader} and picks one by probing. */
@Slf4j
public final class DriverRegistry {

    private final List<Driver> drivers;

    public DriverRegistry(List<Driver> drivers) {
        this.drivers = List.copyOf(drivers);
    }

    public static DriverRegistry fromServiceLoader() {
        List<Driver> found = ServiceLoader.load(Driver.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        log.debug("loaded {} driver(s): {}", found.size(), found.stream().map(Driver::id).toList());
        return new DriverRegistry(found);
    }

    /** The first driver that recognizes the device behind {@code exec}. */
    public Optional<Driver> identify(CliExecutor exec) throws IOException {
        for (Driver driver : drivers) {
            if (driver.recognizes(exec)) {
                return Optional.of(driver);
            }
        }
        return Optional.empty();
    }

    public List<Driver> all() {
        return drivers;
    }
}
