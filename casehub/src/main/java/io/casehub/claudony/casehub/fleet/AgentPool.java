package io.casehub.claudony.casehub.fleet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares pool capacity for a {@link PooledAgent}. Place on the same class.
 *
 * <pre>{@code
 * @PooledAgent(name = "code-reviewer")
 * @AgentPool(minActive = 2, maxActive = 10, eviction = EvictionStrategy.MEMORY_WEIGHTED)
 * public class CodeReviewerAgent { }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentPool {

    /** Minimum pre-warmed sessions. */
    int minActive() default 0;

    /** Maximum concurrent active sessions. */
    int maxActive() default 10;

    /** Eviction strategy when pool is at capacity. */
    EvictionStrategy eviction() default EvictionStrategy.MEMORY_WEIGHTED;
}
