package io.casehub.claudony.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelEventBusTest {

    private ChannelEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ChannelEventBus();
    }

    @Test
    void subscribe_returnsMultiThatReceivesEmittedTicks() {
        List<Integer> received = new CopyOnWriteArrayList<>();
        bus.subscribe("chan-a").subscribe().with(received::add);

        bus.emit("chan-a");
        bus.emit("chan-a");

        assertThat(received).hasSize(2);
    }

    @Test
    void emit_withNoSubscribers_isNoOp() {
        // Must not throw
        bus.emit("chan-nobody");
    }

    @Test
    void emit_onlyDeliverstToMatchingChannel() {
        List<Integer> aReceived = new CopyOnWriteArrayList<>();
        List<Integer> bReceived = new CopyOnWriteArrayList<>();

        bus.subscribe("chan-a").subscribe().with(aReceived::add);
        bus.subscribe("chan-b").subscribe().with(bReceived::add);

        bus.emit("chan-a");

        assertThat(aReceived).hasSize(1);
        assertThat(bReceived).isEmpty();
    }

    @Test
    void subscriberCount_tracksActiveSubscribers() {
        assertThat(bus.subscriberCount("ch")).isZero();

        var sub = bus.subscribe("ch").subscribe().with(t -> {});
        assertThat(bus.subscriberCount("ch")).isEqualTo(1);

        sub.cancel();
        assertThat(bus.subscriberCount("ch")).isZero();
    }

    @Test
    void multipleSubscribers_allReceiveTick() {
        List<Integer> r1 = new CopyOnWriteArrayList<>();
        List<Integer> r2 = new CopyOnWriteArrayList<>();

        bus.subscribe("ch").subscribe().with(r1::add);
        bus.subscribe("ch").subscribe().with(r2::add);

        bus.emit("ch");

        assertThat(r1).hasSize(1);
        assertThat(r2).hasSize(1);
    }
}
