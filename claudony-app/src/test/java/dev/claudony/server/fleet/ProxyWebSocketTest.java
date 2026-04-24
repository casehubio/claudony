package dev.claudony.server.fleet;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ProxyWebSocketTest {

    private static final String WS_BASE = "ws://localhost:8081";
    private static final String API_KEY = "test-api-key-do-not-use-in-prod";

    @Inject PeerRegistry registry;

    @AfterEach
    void cleanup() {
        registry.getAllPeers().stream()
                .filter(p -> p.source() == DiscoverySource.MANUAL)
                .map(PeerRecord::id)
                .toList()
                .forEach(registry::removePeer);
    }

    @Test
    void proxyWebSocket_unknownPeer_closesConnection() throws Exception {
        var closed = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("X-Api-Key", API_KEY)
                .buildAsync(
                        URI.create(WS_BASE + "/ws/proxy/unknown-peer-id/any-session/80/24"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                                closed.set(true);
                                latch.countDown();
                                return null;
                            }
                            @Override
                            public void onError(WebSocket ws, Throwable err) {
                                closed.set(true);
                                latch.countDown();
                            }
                        })
                .join();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(closed.get()).isTrue();
    }

    @Test
    void proxyWebSocket_unauthenticated_rejected() throws Exception {
        var rejected = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(
                            URI.create(WS_BASE + "/ws/proxy/any-peer/any-session/80/24"),
                            new WebSocket.Listener() {
                                @Override
                                public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                                    latch.countDown();
                                    return null;
                                }
                                @Override
                                public void onError(WebSocket ws, Throwable err) {
                                    rejected.set(true);
                                    latch.countDown();
                                }
                            })
                    .join();
        } catch (Exception e) {
            rejected.set(true);
            latch.countDown();
        }

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(rejected.get()).isTrue();
    }

    @Test
    void proxyWebSocket_unreachablePeer_closesGracefully() throws Exception {
        registry.addPeer("proxy-test-peer", "http://localhost:19999",
                "Unreachable", DiscoverySource.MANUAL, TerminalMode.PROXY);

        var closed = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("X-Api-Key", API_KEY)
                .buildAsync(
                        URI.create(WS_BASE + "/ws/proxy/proxy-test-peer/any-session/80/24"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                                closed.set(true);
                                latch.countDown();
                                return null;
                            }
                            @Override
                            public void onError(WebSocket ws, Throwable err) {
                                closed.set(true);
                                latch.countDown();
                            }
                        })
                .join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closed.get()).isTrue();
    }
}
