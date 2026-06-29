package io.hivekeeper.core.crypto;

import io.hivekeeper.core.api.Result;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks secrets in the human-facing strings the gateway hands back or logs. A config-write
 * {@link Result.ConfigApplied} echoes the CLI lines it applied, and those lines embed the passphrase
 * (e.g. {@code ... ascii-key hunter2}) and hive password ({@code hive lab password hunter2}). At-rest
 * encryption protects the persisted blob; this protects the rendered view, browser history, and any log
 * line — so a secret the operator typed is not casually re-exposed downstream.
 */
public final class Secrets {

    // The HiveOS keywords whose following token is a secret. Case-insensitive; the value is a single
    // whitespace-delimited token (PSKs and hive passwords are unquoted single tokens).
    private static final Pattern SECRET_TOKEN = Pattern.compile("(?i)\\b(ascii-key|password)\\s+(\\S+)");
    private static final String MASK = "***";

    private Secrets() {
    }

    /** Replaces the value after {@code ascii-key}/{@code password} with {@code ***}; null-safe. */
    public static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return SECRET_TOKEN.matcher(line).replaceAll("$1 " + MASK);
    }

    /**
     * Returns a copy of the result with secrets masked. Only {@link Result.ConfigApplied} carries CLI
     * lines; every other result type has no secret-bearing strings and is returned unchanged.
     */
    public static Result redactResult(Result result) {
        if (result instanceof Result.ConfigApplied applied) {
            return new Result.ConfigApplied(applied.commandId(), applied.deviceId(),
                    redactAll(applied.commands()), redactAll(applied.outputs()), applied.saved());
        }
        return result;
    }

    private static List<String> redactAll(List<String> lines) {
        return lines.stream().map(Secrets::redact).toList();
    }
}
