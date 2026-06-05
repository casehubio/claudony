package io.casehub.claudony.config;

import io.casehub.claudony.casehub.CaseHubConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CaseHubConfigDefaultsTest {

    @Inject CaseHubConfig config;

    @Test
    void workerExitPollMs_defaultIs5000() {
        assertThat(config.workerExitPollMs()).isEqualTo(5000L);
    }

    @Test
    void workerExitMaxPollFailures_defaultIs3() {
        assertThat(config.workerExitMaxPollFailures()).isEqualTo(3);
    }
}
