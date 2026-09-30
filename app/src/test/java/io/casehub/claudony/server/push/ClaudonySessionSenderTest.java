package io.casehub.claudony.server.push;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudonySessionSenderTest {

    @Test
    void sendDelivers() {
        var sender = new ClaudonySessionSender();
        var received = new ArrayList<String>();
        sender.register("conn-1", received::add);

        sender.send("conn-1", "{\"pool\":\"default\"}");

        assertThat(received).containsExactly("{\"pool\":\"default\"}");
    }

    @Test
    void sendToUnknownConnectionIsNoOp() {
        var sender = new ClaudonySessionSender();
        sender.send("unknown", "{\"test\":true}");
    }

    @Test
    void unregisterStopsDelivery() {
        var sender = new ClaudonySessionSender();
        var received = new ArrayList<String>();
        sender.register("conn-1", received::add);
        sender.unregister("conn-1");

        sender.send("conn-1", "{\"test\":true}");

        assertThat(received).isEmpty();
    }

    @Test
    void failingHandlerEvictsConnection() {
        var sender = new ClaudonySessionSender();
        sender.register("conn-1", msg -> { throw new RuntimeException("broken"); });

        sender.send("conn-1", "msg");

        assertThat(sender.connectionCount()).isZero();
    }

    @Test
    void connectionCount() {
        var sender = new ClaudonySessionSender();
        assertThat(sender.connectionCount()).isZero();
        sender.register("a", msg -> {});
        sender.register("b", msg -> {});
        assertThat(sender.connectionCount()).isEqualTo(2);
    }
}
