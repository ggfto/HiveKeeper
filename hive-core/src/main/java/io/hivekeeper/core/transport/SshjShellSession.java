package io.hivekeeper.core.transport;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Interactive-shell {@link SshSession} for HiveOS. The AP's restricted CLI ({@code ah_cli_ui}) does NOT
 * service the SSH {@code exec} channel — exec returns empty output — so commands must be driven through
 * a PTY-backed shell: send the command, read until the prompt returns, then strip the command echo, the
 * trailing prompt, and any pager artifacts. (Confirmed against a live AP230, 2026-06-11.)
 */
final class SshjShellSession implements SshSession {

    private static final Pattern ANSI = Pattern.compile("\\[[0-9;?]*[ -/]*[@-~]");

    private final SSHClient client;
    private final Session session;
    private final Session.Shell shell;
    private final InputStream in;
    private final OutputStream out;
    private final int quietMillis;

    SshjShellSession(SSHClient client, int quietMillis) throws IOException {
        this.client = client;
        this.quietMillis = quietMillis;
        this.session = client.startSession();
        // A tall terminal minimizes the chance of the pager kicking in.
        this.session.allocatePTY("vt100", 250, 4000, 0, 0, Map.of());
        this.shell = session.startShell();
        this.in = shell.getInputStream();
        this.out = shell.getOutputStream();

        readUntilPrompt();                 // consume login banner + first prompt
        // NOTE: deliberately do NOT issue a "disable paging" command — on HiveOS that mutates the
        // running-config (it persists as a `console page 0` line), which a read-only tool must never
        // do. Paging is handled non-invasively instead: a tall PTY plus answering `--More--` prompts.
    }

    @Override
    public String exec(String command) throws IOException {
        send(command);
        return clean(readUntilPrompt(), command);
    }

    @Override
    public void close() {
        try {
            shell.close();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            session.close();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void send(String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads until the output settles on a CLI prompt, or {@code quietMillis} passes with no new data. */
    private String readUntilPrompt() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long deadline = System.currentTimeMillis() + quietMillis;
        int pagerAnsweredAt = -1;   // buf size at which we last answered a "--More--" prompt

        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int n = in.read(chunk, 0, Math.min(available, chunk.length));
                if (n > 0) {
                    buf.write(chunk, 0, n);
                    String text = stripAnsi(buf.toString(StandardCharsets.UTF_8));
                    // Answer the pager ONLY when "--More--" is at the tail (the AP is waiting), and only once
                    // per occurrence. The old whole-buffer check re-sent a space on every chunk once a single
                    // "--More--" had scrolled by, spamming the pager so a large paged read (e.g. `show log
                    // buffered`) never settled and timed out empty.
                    if (endsWithMore(text)) {
                        if (buf.size() != pagerAnsweredAt) {
                            out.write(' ');
                            out.flush();
                            pagerAnsweredAt = buf.size();
                        }
                    } else if (looksLikePrompt(text)) {
                        break;
                    }
                    deadline = System.currentTimeMillis() + quietMillis;  // got data, keep going
                }
            } else {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return stripAnsi(buf.toString(StandardCharsets.UTF_8));
    }

    /** True when the output is sitting at a pager prompt (HiveOS prints "--More--" with no trailing newline
     *  and waits for a key). Only the tail matters — a "--More--" already scrolled into the backlog is done. */
    static boolean endsWithMore(String text) {
        if (text == null) {
            return false;
        }
        String tail = text.substring(Math.max(0, text.length() - 24)).strip();
        return tail.endsWith("--More--") || tail.endsWith("--More--)");
    }

    static boolean looksLikePrompt(String text) {
        int nl = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
        String lastLine = (nl >= 0 ? text.substring(nl + 1) : text).strip();
        return !lastLine.isEmpty() && (lastLine.endsWith("#") || lastLine.endsWith(">"))
                && !lastLine.contains(" ");
    }

    private static String stripAnsi(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    /** Drops the leading command echo and the trailing prompt/blank lines. */
    private static String clean(String raw, String command) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int start = 0;
        if (lines.length > 0 && lines[0].strip().endsWith(command.strip())) {
            start = 1;
        }
        int end = lines.length;
        while (end > start) {
            String t = lines[end - 1].strip();
            if (t.isEmpty() || t.endsWith("#") || t.endsWith(">")) {
                end--;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]);
            if (i < end - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
