package io.casehub.claudony.casehub.fleet;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class StepScalingPolicy implements ScalingPolicy {

    private final List<ScalingStep> steps;

    public StepScalingPolicy(List<ScalingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        validateSteps(steps);
        this.steps = steps.stream()
            .sorted(Comparator.comparingDouble(ScalingStep::threshold).reversed())
            .toList();
    }

    @Override
    public ScalingDecision evaluate(PoolSnapshot snapshot) {
        double fillRatio = snapshot.fillRatio();
        ScalingStep matchedScaleIn = null;

        for (var step : steps) {
            if (step.adjustment() > 0 && fillRatio >= step.threshold()) {
                return ScalingDecision.scaleOut(step.adjustment(),
                    "fillRatio %.0f%% >= step threshold %.0f%%"
                        .formatted(fillRatio * 100, step.threshold() * 100));
            }
            if (step.adjustment() < 0 && fillRatio <= step.threshold()) {
                matchedScaleIn = step;
            }
        }

        if (matchedScaleIn != null) {
            return ScalingDecision.scaleIn(-matchedScaleIn.adjustment(),
                "fillRatio %.0f%% <= step threshold %.0f%%"
                    .formatted(fillRatio * 100, matchedScaleIn.threshold() * 100));
        }

        return ScalingDecision.none();
    }

    static void validateSteps(List<ScalingStep> steps) {
        for (var step : steps) {
            if (step.threshold() <= 0.0 || step.threshold() >= 1.0) {
                throw new IllegalArgumentException(
                    "threshold must be in (0.0, 1.0): " + step.threshold());
            }
            if (step.adjustment() == 0) {
                throw new IllegalArgumentException("zero adjustment is meaningless");
            }
        }
        var dupes = steps.stream().map(ScalingStep::threshold)
            .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
            .entrySet().stream().filter(e -> e.getValue() > 1).toList();
        if (!dupes.isEmpty()) {
            throw new IllegalArgumentException("duplicate thresholds: " + dupes);
        }
        double lowestScaleOut = steps.stream()
            .filter(s -> s.adjustment() > 0).mapToDouble(ScalingStep::threshold)
            .min().orElse(Double.MAX_VALUE);
        double highestScaleIn = steps.stream()
            .filter(s -> s.adjustment() < 0).mapToDouble(ScalingStep::threshold)
            .max().orElse(-1.0);
        if (highestScaleIn >= lowestScaleOut) {
            throw new IllegalArgumentException(
                "scale-in thresholds must be strictly below all scale-out thresholds");
        }
    }
}
