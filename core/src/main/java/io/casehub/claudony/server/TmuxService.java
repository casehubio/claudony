package io.casehub.claudony.server;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class TmuxService {

    public List<String> listSessionNames() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("tmux", "list-sessions", "-F", "#{session_name}");
        pb.redirectErrorStream(true);
        var process = pb.start();
        int exit = process.waitFor();
        if (exit != 0) return List.of();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines()
                    .filter(l -> !l.isBlank())
                    .collect(Collectors.toList());
        }
    }

    public void createSession(String name, String workingDir, String command)
            throws IOException, InterruptedException {
        var p1 = new ProcessBuilder("tmux", "new-session", "-d", "-s", name, "-c", workingDir)
                .redirectErrorStream(true).start();
        p1.getInputStream().transferTo(OutputStream.nullOutputStream());
        p1.waitFor();
        var p2 = new ProcessBuilder("tmux", "send-keys", "-t", name, command, "Enter")
                .redirectErrorStream(true).start();
        p2.getInputStream().transferTo(OutputStream.nullOutputStream());
        p2.waitFor();
    }

    /**
     * Creates a tmux session that runs the command via {@code sh -c} so the session closes
     * when the command exits. {@code remain-on-exit off} is set explicitly to override
     * any user ~/.tmux.conf setting. Use for CaseHub workers where session lifetime == command lifetime.
     */
    public void createWorkerSession(String name, String workingDir, String command)
            throws IOException, InterruptedException {
        var p1 = new ProcessBuilder("tmux", "new-session", "-d", "-s", name, "-c", workingDir,
                "--", "sh", "-c", command)
                .redirectErrorStream(true).start();
        p1.getInputStream().transferTo(OutputStream.nullOutputStream());
        p1.waitFor();
        // Explicit enforcement — overrides any remain-on-exit on in ~/.tmux.conf
        var p2 = new ProcessBuilder("tmux", "set-option", "-t", name, "remain-on-exit", "off")
                .redirectErrorStream(true).start();
        p2.getInputStream().transferTo(OutputStream.nullOutputStream());
        p2.waitFor();
    }

    public void setSessionOption(String name, String key, String value)
            throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "set-option", "-t", name, key, value)
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
    }

    public Optional<String> getSessionOption(String name, String key)
            throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "show-options", "-t", name, "-v", key)
                .redirectErrorStream(false).start();
        var value = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        if (exit != 0 || value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }

    public void killSession(String name) throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "kill-session", "-t", name)
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
    }

    public void sendKeys(String sessionName, String text)
            throws IOException, InterruptedException {
        // -l: literal mode — prevents tmux interpreting words like "Escape"/"Enter" as key names
        var p = new ProcessBuilder("tmux", "send-keys", "-t", sessionName, "-l", text)
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
    }

    public String capturePane(String sessionName, int lines)
            throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
                "tmux", "capture-pane", "-t", sessionName, "-p", "-S", String.valueOf(-lines))
                .redirectErrorStream(true);
        var p = pb.start();
        try (var in = p.getInputStream()) {
            var output = new String(in.readAllBytes());
            p.waitFor();
            return output;
        }
    }

    public String displayMessage(String sessionName, String format)
            throws IOException, InterruptedException {
        // -t must precede -p format; tmux 3.6a rejects -p format -t target as "too many arguments"
        var p = new ProcessBuilder("tmux", "display-message", "-t", sessionName, "-p", format)
                .redirectErrorStream(false).start();
        var output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

    public Process attachSession(String sessionName) throws IOException {
        var pb = new ProcessBuilder("tmux", "attach-session", "-t", sessionName);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public boolean sessionExists(String name) throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "has-session", "-t", name)
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        return p.waitFor() == 0;
    }

    public String tmuxVersion() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("tmux", "-V").redirectErrorStream(true);
        var p = pb.start();
        try (var in = p.getInputStream()) {
            var version = new String(in.readAllBytes()).trim();
            p.waitFor();
            return version;
        }
    }
}
