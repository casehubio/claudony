package dev.claudony.agent.terminal;

import java.io.IOException;

public interface TerminalAdapter {
    String name();
    boolean isAvailable();
    void openSession(String sessionName) throws IOException, InterruptedException;
}
