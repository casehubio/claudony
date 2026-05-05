package dev.claudony.server.strategy;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EventsOnlyStrategyTest {

    private EventsOnlyStrategy strategy;

    @BeforeEach
    void setUp() { strategy = new EventsOnlyStrategy(); }

    @Test
    void subscribe_emitsInitialSnapshot() {
        var subscriber = strategy.subscribe("case-1", () -> "data: initial\n\n")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.assertNotTerminated();
        assertThat(subscriber.getItems()).containsExactly("data: initial\n\n");
    }

    @Test
    void onLifecycleEvent_pushesToAllSubscribersForCase() throws Exception {
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2);

        strategy.subscribe("case-2", () -> "data: snap\n\n")
                .subscribe().with(e -> { received1.add(e); if (received1.size() == 2) latch.countDown(); });
        strategy.subscribe("case-2", () -> "data: snap\n\n")
                .subscribe().with(e -> { received2.add(e); if (received2.size() == 2) latch.countDown(); });

        strategy.onLifecycleEvent("case-2");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received1).hasSize(2);
        assertThat(received2).hasSize(2);
    }

    @Test
    void onLifecycleEvent_doesNotPushToOtherCase() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        strategy.subscribe("case-A", () -> "data: A\n\n")
                .subscribe().with(received::add);

        strategy.onLifecycleEvent("case-B");

        Thread.sleep(100);
        assertThat(received).hasSize(1); // only the initial snapshot
    }

    @Test
    void clientDisconnect_removesEmitter_noLeak() {
        var subscriber = strategy.subscribe("case-3", () -> "data: x\n\n")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.cancel();

        assertThatCode(() -> strategy.onLifecycleEvent("case-3")).doesNotThrowAnyException();
        assertThat(strategy.emitterCount("case-3")).isZero();
    }

    @Test
    void unknownCase_onLifecycleEvent_isNoOp() {
        assertThatCode(() -> strategy.onLifecycleEvent("no-such-case")).doesNotThrowAnyException();
    }
}
