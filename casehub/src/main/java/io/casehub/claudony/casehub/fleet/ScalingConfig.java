package io.casehub.claudony.casehub.fleet;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public sealed interface ScalingConfig
        permits ScalingConfig.TargetTrackingConfig,
                ScalingConfig.StepConfig,
                ScalingConfig.CustomScalingConfig,
                ScalingConfig.NoScalingConfig,
                ScalingConfig.DemandPressureConfig {

    Duration cooldown();

    Duration scaleInCooldown();

    default String type() {
        return switch (this) {
            case TargetTrackingConfig t -> "target-tracking";
            case StepConfig s -> "step";
            case CustomScalingConfig c -> "custom";
            case NoScalingConfig n -> "none";
            case DemandPressureConfig d -> "demand-pressure";
        };
    }


    record TargetTrackingConfig(
            double targetFillRatio,
            Duration cooldown,
            Duration scaleInCooldown
    ) implements ScalingConfig {
        public TargetTrackingConfig {
            if (targetFillRatio <= 0.0 || targetFillRatio > 1.0) {
                throw new IllegalArgumentException(
                        "targetFillRatio must be in (0.0, 1.0]");
            }
            if (cooldown == null) {cooldown = Duration.ofSeconds(60);}
            if (scaleInCooldown == null) {scaleInCooldown = cooldown;}
        }
    }

    record StepConfig(
            List<ScalingStep> steps,
            Duration cooldown,
            Duration scaleInCooldown
    ) implements ScalingConfig {
        public StepConfig {
            Objects.requireNonNull(steps);
            if (steps.isEmpty()) {throw new IllegalArgumentException("steps must not be empty");}
            StepScalingPolicy.validateSteps(steps);
            if (cooldown == null) {cooldown = Duration.ofSeconds(60);}
            if (scaleInCooldown == null) {scaleInCooldown = cooldown;}
        }
    }

    record CustomScalingConfig(
            String beanName,
            Duration cooldown,
            Duration scaleInCooldown
    ) implements ScalingConfig {
        public CustomScalingConfig {
            Objects.requireNonNull(beanName);
            if (beanName.isBlank()) {throw new IllegalArgumentException("beanName must not be blank");}
            if (cooldown == null) {cooldown = Duration.ofSeconds(60);}
            if (scaleInCooldown == null) {scaleInCooldown = cooldown;}
        }
    }

    record NoScalingConfig() implements ScalingConfig {
        public static final NoScalingConfig INSTANCE = new NoScalingConfig();

        @Override
        public Duration cooldown()        {return Duration.ZERO;}

        @Override
        public Duration scaleInCooldown() {return Duration.ZERO;}
    }

    record DemandPressureConfig(
            int exhaustionThreshold,
            long latencyThresholdMs,
            Duration cooldown,
            Duration scaleInCooldown
    ) implements ScalingConfig {
        public DemandPressureConfig {
            if (exhaustionThreshold < 0) {
                throw new IllegalArgumentException(
                        "exhaustionThreshold must be non-negative");
            }
            if (latencyThresholdMs <= 0) {
                throw new IllegalArgumentException(
                        "latencyThresholdMs must be positive");
            }
            if (cooldown == null) {cooldown = Duration.ofSeconds(60);}
            if (scaleInCooldown == null) {scaleInCooldown = cooldown;}
        }
    }
}
