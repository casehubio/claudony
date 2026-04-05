package dev.remotecc.server.auth;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AuthResourceTest {

    @InjectMock
    CredentialStore credentialStore;

    @BeforeEach
    void setUp() {
        // Default: store has credentials — token is required
        Mockito.when(credentialStore.isEmpty()).thenReturn(false);
    }

    @Test
    @TestSecurity(user = "test", roles = "user")
    void postInviteReturnsUrlWithToken() {
        given().when().post("/auth/invite")
            .then().statusCode(200)
            .body("url", containsString("/auth/register?token="));
    }

    @Test
    void postInviteWithoutAuthReturns401() {
        given().when().post("/auth/invite")
            .then().statusCode(401);
    }

    @Test
    void getRegisterWithInvalidTokenReturns403() {
        given().when().get("/auth/register?token=bad-token-does-not-exist")
            .then().statusCode(403);
    }

    @Test
    void getRegisterWithNoTokenServesPageWhenStoreEmpty() {
        // Override: credential store is empty — no token required for first user
        Mockito.when(credentialStore.isEmpty()).thenReturn(true);
        given().when().get("/auth/register")
            .then().statusCode(200);
    }
}
