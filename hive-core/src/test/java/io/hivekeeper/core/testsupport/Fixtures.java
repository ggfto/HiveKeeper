package io.hivekeeper.core.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads captured CLI fixtures from the test classpath. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String load(String classpathPath) {
        try (InputStream in = Fixtures.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
