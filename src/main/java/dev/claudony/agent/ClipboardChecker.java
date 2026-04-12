package dev.claudony.agent;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.io.*;
import java.nio.file.*;

@ApplicationScoped
public class ClipboardChecker {

    private static final Logger LOG = Logger.getLogger(ClipboardChecker.class);

    public boolean isConfigured() throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "show-options", "-g", "set-clipboard")
                .redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            var output = new String(in.readAllBytes()).trim();
            p.waitFor();
            return output.contains("set-clipboard on") || output.contains("set-clipboard external");
        }
    }

    public void fix() throws IOException, InterruptedException {
        var tmuxConf = Path.of(System.getProperty("user.home"), ".tmux.conf");
        Files.writeString(tmuxConf, "\nset -g set-clipboard on\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        var p = new ProcessBuilder("tmux", "source-file", tmuxConf.toString())
                .redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        p.waitFor();
        LOG.info("Clipboard fix applied: set -g set-clipboard on added to ~/.tmux.conf");
    }

    public String statusMessage() {
        try {
            return isConfigured()
                    ? "tmux clipboard: configured ✓"
                    : "tmux clipboard: not configured (run fix to enable)";
        } catch (Exception e) {
            return "tmux clipboard: check failed (" + e.getMessage() + ")";
        }
    }
}
