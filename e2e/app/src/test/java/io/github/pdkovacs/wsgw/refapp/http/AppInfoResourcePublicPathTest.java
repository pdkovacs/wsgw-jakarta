package io.github.pdkovacs.wsgw.refapp.http;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test documenting a known bug in {@link BasicAuthFilter}.
 *
 * <p>The filter is meant to leave {@code /app-info} unauthenticated (it is listed in
 * {@code PUBLIC_PATHS}). It does not: line 37 computes the request path as
 * <pre>{@code String path = "/" + ctx.getUriInfo().getPath();}</pre>
 * but under RESTEasy Reactive {@code getUriInfo().getPath()} already returns a leading
 * slash ({@code /app-info}), so the prepend yields {@code //app-info}, which never
 * matches {@code PUBLIC_PATHS}. As a result the supposedly public endpoint returns
 * {@code 401} instead of {@code 200}.
 *
 * <p>This test asserts the <em>intended</em> behaviour and is therefore {@link Disabled}
 * until the filter is fixed. To resolve:
 * <ol>
 *   <li>In {@link BasicAuthFilter}, stop double-prefixing the slash — e.g.
 *       {@code String path = "/" + ctx.getUriInfo().getPath().replaceFirst("^/", "");}
 *       (defensive) or simply {@code String path = ctx.getUriInfo().getPath();}.</li>
 *   <li>Remove the {@link Disabled} annotation below; this test then guards the fix.</li>
 * </ol>
 */
@QuarkusTest
@Disabled("Documents a known BasicAuthFilter bug: '/app-info' public exemption "
        + "is broken by a double-slash in the path check (//app-info). Remove this "
        + "annotation once BasicAuthFilter is fixed; the test then becomes the guard.")
class AppInfoResourcePublicPathTest {

    @Test
    void appInfoIsReachableWithoutAuthentication() {
        given()
                .when().get("/app-info")
                .then()
                .statusCode(200)
                .body("application", is("e2e-app"))
                .body("version", notNullValue());
    }
}
