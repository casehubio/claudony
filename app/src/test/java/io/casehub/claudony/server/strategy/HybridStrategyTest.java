package io.casehub.claudony.server.strategy;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class HybridStrategyTest {

    @Test
    void subscribe_emitsInitialSnapshot() {
        var strategy = new HybridStrategy(60_000); // long heartbeat, won't fire in test
        var received = new CopyOnWriteArrayList<String>();

        strategy.subscribe("case-h1", () -> "data: init\n\n")
                .subscribe().with(received::add);

        assertThat(received).containsExactly("data: init\n\n");
        strategy.cancel();
    }

    @Test
    void heartbeat_emitsSnapshot_afterInterval() throws Exception {
        var strategy = new HybridStrategy(200); // 200ms heartbeat for test
        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2); // initial + 1 heartbeat

        strategy.subscribe("case-h2", () -> "data: tick\n\n")
                .subscribe().with(e -> { received.add(e); latch.countDown(); });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.size()).isGreaterThanOrEqualTo(2);
        assertThat(received).allMatch(e -> e.equals("data: tick\n\n"));
        strategy.cancel();
    }

    @Test
    void lifecycleEvent_stillPushes_betweenHeartbeats() throws Exception {
        var strategy = new HybridStrategy(60_000); // long heartbeat
        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2); // initial + lifecycle

        strategy.subscribe("case-h3", () -> "data: snap\n\n")
                .subscribe().with(e -> { received.add(e); latch.countDown(); });

        strategy.onLifecycleEvent("case-h3");

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
        strategy.cancel();
    }

    @Test
    void cancel_stopsHeartbeat() throws Exception {
        var strategy = new HybridStrategy(100);
        var received = new CopyOnWriteArrayList<String>();

        strategy.subscribe("case-h4", () -> "data: x\n\n")
                .subscribe().with(received::add);

        strategy.cancel();
        int countAfterCancel = received.size();
        Thread.sleep(300);

        assertThat(received.size()).isLessThanOrEqualTo(countAfterCancel + 1);
    }
}
