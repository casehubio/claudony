package io.casehub.claudony.server.api;

import io.casehub.claudony.casehub.fleet.script.FleetScriptResult;
import io.casehub.claudony.casehub.fleet.script.NodeResult;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ClaudonyFleetApiTest {

    @Test
    void mcpDomainAnnotation() {
        var annotation = ClaudonyFleetApi.class.getAnnotation(McpDomain.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("claudony/fleet");
        assertThat(annotation.app()).isEqualTo("claudony");
        assertThat(annotation.basePath()).isEqualTo("/api/claudony/fleet");
    }

    @Test
    void executeFleetScriptIsMutation() throws NoSuchMethodException {
        var method = ClaudonyFleetApi.class.getMethod("executeFleetScript", String.class);
        var mutation = method.getAnnotation(PlatformMutation.class);
        assertThat(mutation).isNotNull();
        assertThat(mutation.value()).contains("fleet deployment script");
    }

    @Test
    void resultStructure() {
        var result = new FleetScriptResult(List.of(
                NodeResult.ok("pool-1", "pool", "created"),
                NodeResult.failed("channel-1", "channel", "error")));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).name()).isEqualTo("channel-1");
    }
}
