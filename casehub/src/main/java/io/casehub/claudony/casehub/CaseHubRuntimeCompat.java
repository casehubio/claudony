package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import org.jboss.logging.Logger;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Reflection-based compat shim for CaseHubRuntime.signal().
 *
 * The return type changed from void (engine SNAPSHOT build ≤128) to
 * CompletionStage&lt;Void&gt; (later builds). Compiled bytecode targeting either
 * version throws NoSuchMethodError against the other. This helper resolves the
 * method by name and handles both return types, so Claudony works with any
 * SNAPSHOT build in the stability window.
 *
 * Remove this class once the engine API stabilises on CompletionStage.
 */
class CaseHubRuntimeCompat {

    private static final Logger LOG = Logger.getLogger(CaseHubRuntimeCompat.class);

    static void signal(CaseHubRuntime runtime, UUID caseId, String key, Object value) {
        try {
            Method m = CaseHubRuntime.class.getMethod("signal", UUID.class, String.class, Object.class);
            Object result = m.invoke(runtime, caseId, key, value);
            if (result instanceof CompletionStage<?> cs) {
                cs.toCompletableFuture().join();
            }
        } catch (Throwable t) {
            LOG.warnf(t, "Failed to signal %s=%s for caseId=%s — when-guard may not clear",
                    key, value, caseId);
        }
    }
}
