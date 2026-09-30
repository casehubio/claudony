package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoolEventEmitterTest {

    @Test
    void emitScalingDecision_broadcastsToCorrectTopic() {
        var topics = new ArrayList<String>();
        var payloads = new ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            topics.add(topic);
            payloads.add(json);
            return 1L;
        });

        emitter.emitScalingDecision("default",
            ScalingDecision.scaleOut(2, "high fill"), 8, 10);

        assertThat(topics).containsExactly("pool:default:scaling");
        assertThat(payloads.get(0)).contains("\"direction\":\"OUT\"");
        assertThat(payloads.get(0)).contains("\"count\":2");
        assertThat(payloads.get(0)).contains("\"previousMax\":8");
        assertThat(payloads.get(0)).contains("\"newMax\":10");
    }

    @Test
    void emitSessionEvent_broadcastsToCorrectTopic() {
        var topics = new ArrayList<String>();
        var payloads = new ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            topics.add(topic);
            payloads.add(json);
            return 1L;
        });

        emitter.emitSessionEvent("default", "suspended", "sess-1", "worker-a", "eviction");

        assertThat(topics).containsExactly("pool:default:session");
        assertThat(payloads.get(0)).contains("\"event\":\"suspended\"");
        assertThat(payloads.get(0)).contains("\"instanceId\":\"sess-1\"");
        assertThat(payloads.get(0)).contains("\"identity\":\"worker-a\"");
    }

    @Test
    void emitHealthChange_broadcastsToCorrectTopic() {
        var topics = new ArrayList<String>();
        var payloads = new ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            topics.add(topic);
            payloads.add(json);
            return 1L;
        });

        emitter.emitHealthChange("default", "HEALTHY", "DEGRADED", "fill ratio exceeded 0.9");

        assertThat(topics).containsExactly("pool:default:health");
        assertThat(payloads.get(0)).contains("\"previous\":\"HEALTHY\"");
        assertThat(payloads.get(0)).contains("\"current\":\"DEGRADED\"");
    }

    @Test
    void nullIdentityRendersEmpty() {
        var payloads = new ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            payloads.add(json);
            return 1L;
        });

        emitter.emitSessionEvent("default", "created", "s-1", null, "provision");

        assertThat(payloads.get(0)).contains("\"identity\":\"\"");
    }

    @Test
    void reasonWithQuotesEscaped() {
        var payloads = new ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            payloads.add(json);
            return 1L;
        });

        emitter.emitScalingDecision("default",
            ScalingDecision.scaleOut(1, "fill ratio 0.9 > target \"0.7\""), 5, 6);

        assertThat(payloads.get(0)).contains("\\\"0.7\\\"");
    }
}
