package io.casehub.claudony.casehub.fleet;

import java.time.Duration;
import java.time.Instant;

public record ScalingState(
    ScalingDecision lastDecision,
    Instant lastDecisionTime,
    Instant lastScaleOut,
    Instant lastScaleIn,
    ScalingConfig config
) {
    public Duration cooldownRemaining(Instant now) {
        if (config instanceof ScalingConfig.NoScalingConfig) return Duration.ZERO;
        Duration remaining = Duration.ZERO;
        if (lastScaleOut != null) {
            Duration sinceOut = Duration.between(lastScaleOut, now);
            if (sinceOut.compareTo(config.cooldown()) < 0) {
                remaining = config.cooldown().minus(sinceOut);
            }
        }
        if (lastScaleIn != null) {
            Duration sinceIn = Duration.between(lastScaleIn, now);
            if (sinceIn.compareTo(config.scaleInCooldown()) < 0) {
                Duration inRemaining = config.scaleInCooldown().minus(sinceIn);
                if (inRemaining.compareTo(remaining) > 0) remaining = inRemaining;
            }
        }
        return remaining;
    }
}
