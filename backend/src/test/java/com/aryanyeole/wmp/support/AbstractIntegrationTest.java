package com.aryanyeole.wmp.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real Postgres.
 *
 * Deliberately does NOT use @Testcontainers/@Container. That JUnit5
 * extension only guarantees a static container is reused across @Test
 * methods within one class — per Testcontainers' own docs, "there is no
 * special support" for reuse across test CLASSES, and under Maven
 * Failsafe it was stopping and restarting the container between every IT
 * class while Spring's context cache kept reusing the same DataSource
 * bean pointed at the now-dead old container. See
 * docs/incidents/test-suite-pool-exhaustion.md.
 *
 * This instead follows Testcontainers' documented "singleton container"
 * pattern: start the container once in a static initializer block, so it
 * is genuinely started exactly once per JVM/fork and never stopped by any
 * per-class JUnit lifecycle hook. Ryuk (started automatically by
 * Testcontainers core, independent of the JUnit5 extension) is
 * responsible for tearing it down when the JVM exits.
 *
 * @ServiceConnection still works with no @Testcontainers/@Container
 * present: it's wired up by Spring's own ServiceConnectionContextCustomizerFactory,
 * which scans the test class hierarchy for annotated fields regardless of
 * the Testcontainers JUnit extension.
 *
 * wmp.rate-limit.login.enabled=false is set here, on the one class every IT
 * already extends, rather than under the pre-existing "test" Spring profile
 * (application-test.yml): only 3 of the 12 IT classes actually activate
 * "test", but 5 separate classes call the real POST /api/v1/auth/login
 * through MockMvc — which runs RateLimitFilter exactly like a real request,
 * same Spring context, same in-memory bucket, same simulated client address
 * for every one of them. Gating on "test" would leave most of that traffic
 * exposed to a live limiter never meant to police it. @TestPropertySource
 * is merged in from superclasses (unlike plain @ActiveProfiles-driven YAML,
 * which a subclass could otherwise not fully control) and takes precedence
 * over any profile-specific document, so this reaches every IT regardless
 * of which profile(s) it separately activates.
 */
@TestPropertySource(properties = "wmp.rate-limit.login.enabled=false")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
    }
}
