package dev.claudony.agent;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ClipboardCheckerTest {

    @Inject ClipboardChecker checker;

    @Test
    void isConfiguredReturnsBooleanWithoutThrowing() {
        assertDoesNotThrow(() -> checker.isConfigured());
    }

    @Test
    void statusMessageIsNonEmpty() {
        assertDoesNotThrow(() -> {
            var msg = checker.statusMessage();
            assertNotNull(msg);
            assertFalse(msg.isBlank());
        });
    }
}
