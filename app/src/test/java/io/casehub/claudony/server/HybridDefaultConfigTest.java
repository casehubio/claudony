package io.casehub.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(HybridDefaultConfigTest.HybridProfile.class)
@TestSecurity(user = "test", roles = "user")
class HybridDefaultConfigTest {

    public static class HybridProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("claudony.case-worker-update", "hybrid");
        }
    }

    @Inject CaseEventBroadcaster broadcaster;

    @Test
    void selectedStrategy_isHybrid_whenConfiguredAsHybrid() {
        assertThat(broadcaster.strategyType()).isEqualTo("hybrid");
    }
}
