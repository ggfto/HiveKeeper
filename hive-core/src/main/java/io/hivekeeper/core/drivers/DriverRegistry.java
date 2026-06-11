package io.hivekeeper.core.drivers;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/** Discovers {@link Driver} implementations via {@link ServiceLoader} and selects one by fingerprint. */
public final class DriverRegistry {

    private final List<Driver> drivers;

    public DriverRegistry(List<Driver> drivers) {
        this.drivers = List.copyOf(drivers);
    }

    public static DriverRegistry fromServiceLoader() {
        List<Driver> found = ServiceLoader.load(Driver.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return new DriverRegistry(found);
    }

    /** The first driver that recognizes the device from its {@code show version} output. */
    public Optional<Driver> select(String showVersionOutput) {
        return drivers.stream().filter(d -> d.supports(showVersionOutput)).findFirst();
    }

    public List<Driver> all() {
        return drivers;
    }
}
