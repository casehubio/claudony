package dev.claudony;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Condition-based waiting for integration tests.
 *
 * <p>Replaces Thread.sleep with polling: checks a condition every 50 ms until
 * it becomes true or a timeout elapses. Faster on fast machines, reliably fails
 * on slow ones with a clear message rather than silently passing wrong results.
 */
public final class Await {

    private static final long POLL_MS = 50;

    private Await() {}

    /**
     * Polls {@code condition} every 50 ms until it returns {@code true} or
     * {@code timeout} elapses.
     *
     * @throws AssertionError with {@code timeoutMessage} if deadline is exceeded
     */
    public static void until(BooleanSupplier condition, Duration timeout, String timeoutMessage) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting: " + timeoutMessage, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout + ": " + timeoutMessage);
    }

    /** Convenience overload with 3-second default timeout. */
    public static void until(BooleanSupplier condition, String timeoutMessage) {
        until(condition, Duration.ofSeconds(3), timeoutMessage);
    }
}
