package dev.claudony.agent.terminal;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ITerm2AdapterTest {

    @Inject ITerm2Adapter adapter;

    @Test
    void adapterNameIsIterm2() {
        assertEquals("iterm2", adapter.name());
    }

    @Test
    void isAvailableReturnsBooleanWithoutThrowing() {
        assertDoesNotThrow(() -> adapter.isAvailable());
    }
}
