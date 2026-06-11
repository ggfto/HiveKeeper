package io.hivekeeper.protocol;

import java.util.UUID;

/** Protocol constants and helpers. */
public final class Protocol {

    /** Bump on any breaking change to the {@link Frame} envelope; exchanged in {@link Frame.Hello}. */
    public static final String VERSION = "1.0";

    private Protocol() {
    }

    public static String newJobId() {
        return UUID.randomUUID().toString();
    }
}
