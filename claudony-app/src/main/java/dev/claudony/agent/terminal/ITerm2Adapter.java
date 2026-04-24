package dev.claudony.agent.terminal;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.io.*;

@ApplicationScoped
public class ITerm2Adapter implements TerminalAdapter {

    private static final Logger LOG = Logger.getLogger(ITerm2Adapter.class);

    @Override
    public String name() { return "iterm2"; }

    @Override
    public boolean isAvailable() {
        try {
            var p = new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to (name of processes) contains \"iTerm2\"")
                    .redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                var output = new String(in.readAllBytes()).trim();
                p.waitFor();
                return "true".equals(output);
            }
        } catch (IOException | InterruptedException e) {
            LOG.debugf("iTerm2 check failed: %s", e.getMessage());
            return false;
        }
    }

    @Override
    public void openSession(String sessionName) throws IOException, InterruptedException {
        var script = """
                tell application "iTerm"
                    activate
                    create window with default profile
                    tell current session of current window
                        write text "tmux -CC attach-session -t %s"
                    end tell
                end tell
                """.formatted(sessionName);
        var p = new ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        p.waitFor();
        LOG.infof("Opened iTerm2 window for session '%s'", sessionName);
    }
}
