package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.AgentPoolManagerRegistry;
import io.casehub.claudony.casehub.fleet.ScalingConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.BadRequestException;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoolServiceTest {

    private PoolService service;

    @BeforeEach
    void setUp() {
        var defRegistry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        var mgrRegistry = new AgentPoolManagerRegistry();
        service = new PoolService(defRegistry, mgrRegistry, null, new SimpleMeterRegistry());
    }

    @Test
    void parseTargetTracking() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 0.8, null, null, null, null, "30s", null);
        var config = service.parseScalingConfig(req);
        assertInstanceOf(ScalingConfig.TargetTrackingConfig.class, config);
        var tt = (ScalingConfig.TargetTrackingConfig) config;
        assertEquals(0.8, tt.targetFillRatio());
        assertEquals(Duration.ofSeconds(30), tt.cooldown());
    }

    @Test
    void parseTargetTrackingDefaultRatio() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", null, null, null, null, null, null, null);
        var config = service.parseScalingConfig(req);
        assertEquals(0.7, ((ScalingConfig.TargetTrackingConfig) config).targetFillRatio());
    }

    @Test
    void parseStep() {
        var steps = List.of(new ScalingStepInput(0.8, 2), new ScalingStepInput(0.3, -1));
        var req = new PoolUpdateRequest(null, null, "step", null, steps, null, null, null, null, null);
        var config = service.parseScalingConfig(req);
        assertInstanceOf(ScalingConfig.StepConfig.class, config);
        assertEquals(2, ((ScalingConfig.StepConfig) config).steps().size());
    }

    @Test
    void parseDemandPressure() {
        var req = new PoolUpdateRequest(null, null, "demand-pressure", null, null, 5, 500L, null, null, null);
        var config = service.parseScalingConfig(req);
        assertInstanceOf(ScalingConfig.DemandPressureConfig.class, config);
        var dp = (ScalingConfig.DemandPressureConfig) config;
        assertEquals(5, dp.exhaustionThreshold());
        assertEquals(500L, dp.latencyThresholdMs());
    }

    @Test
    void parseNone() {
        var req = new PoolUpdateRequest(null, null, "none", null, null, null, null, null, null, null);
        var config = service.parseScalingConfig(req);
        assertInstanceOf(ScalingConfig.NoScalingConfig.class, config);
    }

    @Test
    void parseCustom() {
        var req = new PoolUpdateRequest(null, null, "my-custom-bean", null, null, null, null, null, null, null);
        var config = service.parseScalingConfig(req);
        assertInstanceOf(ScalingConfig.CustomScalingConfig.class, config);
        assertEquals("my-custom-bean", ((ScalingConfig.CustomScalingConfig) config).beanName());
    }

    @Test
    void invalidTargetRatioThrows() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 1.5, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.parseScalingConfig(req));
    }

    @Test
    void emptyStepsThrows() {
        var req = new PoolUpdateRequest(null, null, "step", null, List.of(), null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.parseScalingConfig(req));
    }

    @Test
    void nullStepsThrows() {
        var req = new PoolUpdateRequest(null, null, "step", null, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.parseScalingConfig(req));
    }

    @Test
    void demandPressureMissingFieldsThrows() {
        var req = new PoolUpdateRequest(null, null, "demand-pressure", null, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.parseScalingConfig(req));
    }

    @Test
    void parseDurationSeconds() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 0.7, null, null, null, null, "90s", "120s");
        var config = (ScalingConfig.TargetTrackingConfig) service.parseScalingConfig(req);
        assertEquals(Duration.ofSeconds(90), config.cooldown());
        assertEquals(Duration.ofSeconds(120), config.scaleInCooldown());
    }

    @Test
    void parseDurationMinutes() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 0.7, null, null, null, null, "5m", null);
        var config = (ScalingConfig.TargetTrackingConfig) service.parseScalingConfig(req);
        assertEquals(Duration.ofMinutes(5), config.cooldown());
    }

    @Test
    void parseDurationPlainNumber() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 0.7, null, null, null, null, "120", null);
        var config = (ScalingConfig.TargetTrackingConfig) service.parseScalingConfig(req);
        assertEquals(Duration.ofSeconds(120), config.cooldown());
    }

    @Test
    void invalidDurationThrows() {
        var req = new PoolUpdateRequest(null, null, "target-tracking", 0.7, null, null, null, null, "abc", null);
        assertThrows(BadRequestException.class, () -> service.parseScalingConfig(req));
    }

    @Test
    void buildScalingConfigViewTargetTracking() {
        var config = new ScalingConfig.TargetTrackingConfig(0.8, Duration.ofSeconds(30), Duration.ofSeconds(60));
        var view = service.buildScalingConfigView(config);
        assertEquals(0.8, view.targetFillRatio());
        assertNull(view.steps());
        assertNull(view.exhaustionThreshold());
        assertEquals("30s", view.cooldown());
        assertEquals("60s", view.scaleInCooldown());
    }

    @Test
    void buildScalingConfigViewDemandPressure() {
        var config = new ScalingConfig.DemandPressureConfig(5, 500, Duration.ofSeconds(60), Duration.ofSeconds(120));
        var view = service.buildScalingConfigView(config);
        assertNull(view.targetFillRatio());
        assertEquals(5, view.exhaustionThreshold());
        assertEquals(500L, view.latencyThresholdMs());
        assertEquals("60s", view.cooldown());
    }

    @Test
    void buildScalingConfigViewNone() {
        var view = service.buildScalingConfigView(ScalingConfig.NoScalingConfig.INSTANCE);
        assertNull(view.targetFillRatio());
        assertNull(view.steps());
        assertEquals("0s", view.cooldown());
    }
}
