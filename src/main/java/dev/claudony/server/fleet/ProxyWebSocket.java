package dev.claudony.server.fleet;

import dev.claudony.server.auth.FleetKeyService;
import io.quarkus.websockets.next.*;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket proxy for PROXY-mode peers.
 *
 * Browser connects to ws://local:7777/ws/proxy/{peerId}/{sessionId}/{cols}/{rows}.
 * This endpoint opens an upstream WebSocket to the peer's /ws/{sessionId}/{cols}/{rows}
 * and pipes frames bidirectionally. The browser never needs direct access to the peer.
 *
 * Path is at /ws/proxy/... (6 segments) — no routing conflict with TerminalWebSocket
 * at /ws/{id}/{cols}/{rows} (4 segments).
 */
@WebSocket(path = "/ws/proxy/{peerId}/{sessionId}/{cols}/{rows}")
public class ProxyWebSocket {

    private static final Logger LOG = Logger.getLogger(ProxyWebSocket.class);

    @Inject PeerRegistry peerRegistry;
    @Inject FleetKeyService fleetKeyService;
    @Inject Vertx vertx;

    private io.vertx.mutiny.core.http.HttpClient httpClient;

    @PostConstruct
    void initClient() {
        httpClient = vertx.createHttpClient();
    }

    /** connectionId → upstream WebSocket to the peer */
    private final ConcurrentHashMap<String, io.vertx.mutiny.core.http.WebSocket> upstreams
            = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var peerId     = connection.pathParam("peerId");
        var sessionId  = connection.pathParam("sessionId");
        var cols       = connection.pathParam("cols");
        var rows       = connection.pathParam("rows");

        var peer = peerRegistry.findById(peerId);
        if (peer.isEmpty()) {
            LOG.warnf("Proxy WS: peer not found id=%s — closing", peerId);
            connection.closeAndAwait();
            return;
        }

        var peerUrl = peer.get().url();
        var uri = URI.create(peerUrl);
        var isHttps = "https".equalsIgnoreCase(uri.getScheme());
        var port = uri.getPort() == -1 ? (isHttps ? 443 : 80) : uri.getPort();
        var wsPath = "/ws/" + sessionId + "/" + cols + "/" + rows;

        var options = new WebSocketConnectOptions()
                .setHost(uri.getHost())
                .setPort(port)
                .setURI(wsPath);
        if (isHttps) {
            options.setSsl(true);
        }
        fleetKeyService.getKey().ifPresent(k -> options.addHeader("X-Api-Key", k));

        httpClient
                .webSocket(options)
                .subscribe().with(
                    upstream -> {
                        upstreams.put(connection.id(), upstream);

                        // Forward upstream text to browser
                        upstream.textMessageHandler(text ->
                                connection.sendText(text)
                                        .subscribeAsCompletionStage());

                        // Forward upstream binary to browser — mutiny Buffer → core Buffer
                        upstream.binaryMessageHandler(buf ->
                                connection.sendBinary(buf.getDelegate())
                                        .subscribeAsCompletionStage());

                        // When upstream closes, close the browser connection
                        upstream.closeHandler(() -> {
                            upstreams.remove(connection.id());
                            connection.close().subscribeAsCompletionStage();
                        });

                        upstream.exceptionHandler(e -> {
                            LOG.debugf("Proxy upstream error for %s: %s", peerUrl, e.getMessage());
                            upstreams.remove(connection.id());
                            connection.close().subscribeAsCompletionStage();
                        });

                        LOG.debugf("Proxy WS open: peer=%s session=%s at %sx%s",
                                peerUrl, sessionId, cols, rows);
                    },
                    err -> {
                        LOG.warnf("Proxy WS upstream connect failed to %s: %s", peerUrl, err.getMessage());
                        connection.close().subscribeAsCompletionStage();
                    }
                );
    }

    @OnTextMessage
    public void onText(WebSocketConnection connection, String text) {
        var upstream = upstreams.get(connection.id());
        if (upstream != null) {
            upstream.writeTextMessage(text).subscribeAsCompletionStage();
        }
    }

    @OnBinaryMessage
    public void onBinary(WebSocketConnection connection, byte[] data) {
        var upstream = upstreams.get(connection.id());
        if (upstream != null) {
            upstream.writeBinaryMessage(
                    io.vertx.mutiny.core.buffer.Buffer.buffer(data))
                    .subscribeAsCompletionStage();
        }
    }

    @OnError
    public void onError(Throwable error, WebSocketConnection connection) {
        LOG.debugf("Proxy WS browser error for %s: %s", connection.id(), error.getMessage());
        onClose(connection);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        var upstream = upstreams.remove(connection.id());
        if (upstream != null) {
            upstream.close().subscribeAsCompletionStage();
        }
    }
}
