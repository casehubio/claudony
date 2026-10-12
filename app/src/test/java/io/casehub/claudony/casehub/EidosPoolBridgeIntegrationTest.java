package io.casehub.claudony.casehub;

import io.casehub.claudony.casehub.fleet.EidosPoolBridge;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class EidosPoolBridgeIntegrationTest {

    @Inject
    Instance<EidosPoolBridge> bridgeInstance;

    @Test
    void bridgeNotActiveByDefault() {
        assertThat(bridgeInstance.isResolvable()).isFalse();
    }
}
