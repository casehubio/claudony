package dev.claudony.server.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class InviteServiceTest {

    private InviteService service;

    @BeforeEach
    void setUp() {
        service = new InviteService();
    }

    @Test
    void generateReturnsNonNullToken() {
        assertNotNull(service.generate());
    }

    @Test
    void generateReturnsDifferentTokensEachTime() {
        assertNotEquals(service.generate(), service.generate());
    }

    @Test
    void isValidReturnsTrueForFreshToken() {
        var token = service.generate();
        assertTrue(service.isValid(token));
    }

    @Test
    void isValidReturnsFalseForUnknownToken() {
        assertFalse(service.isValid("does-not-exist"));
    }

    @Test
    void isValidReturnsFalseForExpiredToken() {
        var token = "expired-token";
        service.injectForTest(token, Instant.now().minusSeconds(1));
        assertFalse(service.isValid(token));
    }

    @Test
    void consumeInvalidatesToken() {
        var token = service.generate();
        assertTrue(service.isValid(token));
        service.consume(token);
        assertFalse(service.isValid(token));
    }

    @Test
    void consumeUnknownTokenDoesNotThrow() {
        assertDoesNotThrow(() -> service.consume("no-such-token"));
    }
}
