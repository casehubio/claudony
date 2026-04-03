package dev.remotecc.server;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.util.List;
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
        new ProcessBuilder("tmux", "new-session", "-d", "-s", name, "-c", workingDir)
                .start().waitFor();
        new ProcessBuilder("tmux", "send-keys", "-t", name, command, "Enter")
                .start().waitFor();
    }

    public void killSession(String name) throws IOException, InterruptedException {
        new ProcessBuilder("tmux", "kill-session", "-t", name)
                .start().waitFor();
    }

    public void sendKeys(String sessionName, String text)
            throws IOException, InterruptedException {
        new ProcessBuilder("tmux", "send-keys", "-t", sessionName, text, "")
                .start().waitFor();
    }

    public String capturePane(String sessionName, int lines)
            throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
                "tmux", "capture-pane", "-t", sessionName, "-p", "-S", String.valueOf(-lines));
        var p = pb.start();
        p.waitFor();
        return new String(p.getInputStream().readAllBytes());
    }

    public Process attachSession(String sessionName) throws IOException {
        var pb = new ProcessBuilder("tmux", "attach-session", "-t", sessionName);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public boolean sessionExists(String name) throws IOException, InterruptedException {
        return new ProcessBuilder("tmux", "has-session", "-t", name)
                .start().waitFor() == 0;
    }

    public String tmuxVersion() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("tmux", "-V");
        var p = pb.start();
        p.waitFor();
        return new String(p.getInputStream().readAllBytes()).trim();
    }
}
