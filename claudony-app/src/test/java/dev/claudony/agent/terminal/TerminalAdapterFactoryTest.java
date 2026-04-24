package dev.claudony.agent.terminal;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TerminalAdapterFactoryTest {

    @Inject TerminalAdapterFactory factory;

    @Test
    void factoryReturnsNonNullResult() {
        assertNotNull(factory.resolve());
    }

    @Test
    void resolveWithNoneConfigReturnsEmpty() {
        assertTrue(factory.resolveForConfig("none").isEmpty());
    }

    @Test
    void resolveWithIterm2ConfigReturnsIterm2() {
        var result = factory.resolveForConfig("iterm2");
        assertTrue(result.isPresent());
        assertEquals("iterm2", result.get().name());
    }

    @Test
    void resolveWithAutoConfigReturnsSomethingOrEmpty() {
        assertNotNull(factory.resolveForConfig("auto"));
    }
}
