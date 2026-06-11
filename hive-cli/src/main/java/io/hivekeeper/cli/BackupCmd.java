package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.tasks.storage.GitBackupStore;
import picocli.CommandLine;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "backup", mixinStandardHelpOptions = true,
        description = "Capture running-config (and the separate PPSK/users channel) into a git-backed store.")
final class BackupCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-d", "--dir"}, defaultValue = "hivekeeper-backups",
            description = "Git backup directory (default: ./hivekeeper-backups)")
    Path dir;

    @CommandLine.Option(names = "--no-users",
            description = "Skip the separate PPSK/users channel")
    boolean noUsers;

    @CommandLine.Option(names = "--no-secrets",
            description = "Capture running-config without passwords")
    boolean noSecrets;

    @Override
    public Integer call() {
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        BackupStore store = new GitBackupStore(dir);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), store);

        Command cmd = new Command.BackupConfig(UUID.randomUUID(), ref, !noUsers, !noSecrets);
        try {
            Result result = engine.execute(cmd, new ConsoleEventSink());
            if (result instanceof Result.Backup b) {
                String shortId = b.ref().commitId().length() >= 10
                        ? b.ref().commitId().substring(0, 10) : b.ref().commitId();
                System.out.printf("Backed up %s -> commit %s (%d bytes, users=%s)%n",
                        ref.id(), shortId, b.configBytes(), b.usersIncluded());
                System.out.println("  stored at: " + dir.resolve(b.ref().path()));
                if (!b.usersIncluded() && !noUsers) {
                    System.out.println("  note: PPSK/users channel was not captured (may be unsupported on this firmware)");
                }
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("backup failed: " + e.getMessage());
            return 1;
        }
    }
}
