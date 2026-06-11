package io.hivekeeper.core.drivers;

/** What a config capture should include. Extensible without changing the {@link Driver} signature. */
public record ConfigScope(boolean includeUsers, boolean includeSecrets) {

    public static ConfigScope full() {
        return new ConfigScope(true, true);
    }
}
