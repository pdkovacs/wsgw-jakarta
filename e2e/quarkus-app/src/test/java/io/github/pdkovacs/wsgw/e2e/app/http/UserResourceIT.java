package io.github.pdkovacs.wsgw.e2e.app.http;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Integration-phase counterpart of {@link UserResourceTest}: failsafe runs this
 * (note the *IT suffix) during `mvn verify` against the *packaged* artifact rather
 * than in-JVM. It inherits every test method from the @QuarkusTest above, so the
 * same assertions are re-exercised against the real launched process — proving the
 * failsafe wiring (and the shared E2EAPP_* env vars) end-to-end.
 */
@QuarkusIntegrationTest
class UserResourceIT extends UserResourceTest {
}
