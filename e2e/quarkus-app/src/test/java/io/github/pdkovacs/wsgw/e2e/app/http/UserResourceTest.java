package io.github.pdkovacs.wsgw.e2e.app.http;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Unit-phase smoke test exercising the Basic-auth path end-to-end against the live
 * Quarkus app. Credentials come from E2EAPP_PASSWORD_CREDENTIALS in the test
 * application.properties. This deliberately drives the authenticated flow (the part
 * of BasicAuthFilter that works) rather than the /app-info public exemption, which
 * is currently broken by a double-slash bug in the filter's path check.
 */
@QuarkusTest
class UserResourceTest {

    @Test
    void authenticatedRequestReturnsCurrentUser() {
        given()
                .auth().preemptive().basic("test", "test")
                .when().get("/user")
                .then()
                .statusCode(200)
                .body("userId", is("test"));
    }

    @Test
    void missingCredentialsAreRejected() {
        given()
                .when().get("/user")
                .then()
                .statusCode(401);
    }
}
