package io.github.pdkovacs.wsgw.e2e.app.http;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test for the {@link Public} exemption in {@link BasicAuthFilter}.
 *
 * <p>{@link BasicAuthFilter} skips authentication for resources/methods annotated
 * {@link Public}. {@link AppInfoResource} carries that annotation, so {@code /app-info}
 * must be reachable without credentials.
 */
@QuarkusTest
class AppInfoResourcePublicPathTest {

    @Test
    void appInfoIsReachableWithoutAuthentication() {
        given()
                .when().get("/app-info")
                .then()
                .statusCode(200)
                .body("application", is("wsgw::e2e::app"))
                .body("version", notNullValue());
    }
}
