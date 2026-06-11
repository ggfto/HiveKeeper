package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import picocli.CommandLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "capture", mixinStandardHelpOptions = true,
        description = "Run raw CLI commands over SSH and dump their verbatim output "
                + "(for building golden fixtures and diagnostics).")
final class CaptureCmd implements Callable<Integer> {

    private static final List<String> DEFAULT_COMMANDS =
            List.of("show version", "show interface", "show station", "show running-config");

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-c", "--command"},
            description = "CLI command to run (repeatable). Defaults to a standard inventory set.")
    List<String> commands;

    @CommandLine.Option(names = {"-o", "--out"},
            description = "Also write each command's output to this directory as a .txt file")
    Path out;

    @Override
    public Integer call() {
        List<String> cmds = (commands == null || commands.isEmpty()) ? DEFAULT_COMMANDS : commands;
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);

        try {
            Result result = engine.execute(Command.RunRaw.of(ref, cmds), new ConsoleEventSink());
            if (result instanceof Result.RawCapture cap) {
                for (Map.Entry<String, String> e : cap.outputs().entrySet()) {
                    System.out.println();
                    System.out.println("===== " + e.getKey() + " =====");
                    System.out.println(e.getValue());
                    if (out != null) {
                        writeFile(e.getKey(), e.getValue());
                    }
                }
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("capture failed: " + e.getMessage());
            return 1;
        }
    }

    private void writeFile(String command, String output) {
        try {
            Files.createDirectories(out);
            String name = command.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".txt";
            Files.writeString(out.resolve(name), output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("  (could not write file for '" + command + "': " + e.getMessage() + ")");
        }
    }
}
