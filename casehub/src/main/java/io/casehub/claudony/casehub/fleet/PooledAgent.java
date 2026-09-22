package io.casehub.claudony.casehub.fleet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a pooled agent identity. Place on a class alongside {@link AgentPool}
 * to define a managed agent pool via annotations rather than the fluent DSL.
 *
 * <pre>{@code
 * @PooledAgent(name = "code-reviewer", workingDir = "/workspace/reviews")
 * @AgentPool(minActive = 2, maxActive = 10)
 * public class CodeReviewerAgent { }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PooledAgent {

    /** Agent identity name. Required. */
    String name();

    /** Default working directory. Empty string means unset (null in the definition). */
    String workingDir() default "";

    /** Working directory concurrency policy. */
    WorkingDirPolicy policy() default WorkingDirPolicy.EXCLUSIVE;

    /** CLI command to run. Empty string means unset (defaults to "claude"). */
    String command() default "";
}
