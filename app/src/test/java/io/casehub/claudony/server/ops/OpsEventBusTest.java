package io.casehub.claudony.server.ops;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpsEventBusTest {

    private OpsEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new OpsEventBus();
    }

    @Test
    void subscribeReceivesInitialSnapshot() {
        var subscriber = bus.subscribe(() -> "{\"initial\":true}")
                .subscribe().withSubscriber(AssertSubscriber.create(1));
        subscriber.assertItems("{\"initial\":true}");
    }

    @Test
    void emitPushesToAllSubscribers() {
        var sub1 = bus.subscribe(() -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        var sub2 = bus.subscribe(() -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        bus.emit("{\"type\":\"update\"}");
        sub1.assertItems("snap", "{\"type\":\"update\"}");
        sub2.assertItems("snap", "{\"type\":\"update\"}");
    }

    @Test
    void emitNoOpWhenNoSubscribers() {
        bus.emit("{\"type\":\"update\"}");
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    void subscriberCount() {
        assertEquals(0, bus.subscriberCount());
        var sub = bus.subscribe(() -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        assertEquals(1, bus.subscriberCount());
        sub.cancel();
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    void cancelCleansUp() {
        var sub = bus.subscribe(() -> "snap")
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        sub.cancel();
        bus.emit("after-cancel");
        sub.assertItems("snap");
    }
}
