package io.casehub.claudony.server.ops;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class OpsEventsResourceTest {

    @Test
    void sseEndpointReturnsEventStream() throws Exception {
        int port = io.restassured.RestAssured.port;
        var conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/ops/events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(500);
        try {
            int status = conn.getResponseCode();
            String contentType = conn.getHeaderField("Content-Type");
            assertThat(status).isEqualTo(200);
            assertThat(contentType).contains("text/event-stream");
        } finally {
            conn.disconnect();
        }
    }
}
