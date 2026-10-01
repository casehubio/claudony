package io.casehub.claudony.server.fleet;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PoolEventBusTest {

    private PoolEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new PoolEventBus();
    }

    @Test
    void subscribeReceivesInitialSnapshot() {
        var subscriber = bus.subscribe("test-pool", () -> "{\"initial\":true}")
                .subscribe().withSubscriber(AssertSubscriber.create(1));
        subscriber.assertItems("{\"initial\":true}");
    }

    @Test
    void emitPushesToSubscriber() {
        var subscriber = bus.subscribe("test-pool", () -> "{\"initial\":true}")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        bus.emit("test-pool", "{\"type\":\"scaling\"}");
        subscriber.assertItems("{\"initial\":true}", "{\"type\":\"scaling\"}");
    }

    @Test
    void poolIsolation() {
        var sub1 = bus.subscribe("pool-a", () -> "a")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        var sub2 = bus.subscribe("pool-b", () -> "b")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        bus.emit("pool-a", "event-a");
        sub1.assertItems("a", "event-a");
        sub2.assertItems("b");
    }

    @Test
    void subscriberCount() {
        assertEquals(0, bus.subscriberCount("test-pool"));
        var sub = bus.subscribe("test-pool", () -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        assertEquals(1, bus.subscriberCount("test-pool"));
        sub.cancel();
        assertEquals(0, bus.subscriberCount("test-pool"));
    }

    @Test
    void cancelCleansUp() {
        var sub = bus.subscribe("test-pool", () -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        sub.cancel();
        bus.emit("test-pool", "after-cancel");
        sub.assertItems("snap");
    }
}
