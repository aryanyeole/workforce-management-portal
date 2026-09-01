package com.aryanyeole.wmp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.aryanyeole.wmp.support.AbstractIntegrationTest;

/**
 * Smoke test: the full application context loads cleanly. Was
 * WmpApplicationTests, a plain @SpringBootTest with no Testcontainers — it
 * fell through to application.yml's dev default
 * (jdbc:postgresql://localhost:5433/wmp) and only ever passed locally
 * because a dev Postgres happened to be listening on 5433. The first clean
 * (no ambient Postgres) runner — GitHub Actions CI — surfaced it as
 * "Connection refused"; nothing in this file's own history had ever
 * exercised that condition before. Every other context-loading test in this
 * suite already extends AbstractIntegrationTest; this was the one
 * exception, found by auditing all of them, not assumed fixed by the
 * *IT-classes-only check that first added Testcontainers.
 *
 * Renamed to *IT (from *Tests) so it runs under failsafe instead of
 * surefire — required to pick up Testcontainers' JVM/lifecycle the same
 * way every other IT does; a plain @SpringBootTest under surefire has no
 * access to AbstractIntegrationTest's container start. This also means
 * surefire now has zero matching classes and reports 0 tests, which is
 * expected, not a regression: this was the only *Tests class in the suite.
 *
 * This class's own @SpringBootTest(MOCK) shape — no @AutoConfigureMockMvc,
 * no @ActiveProfiles — is identical to ExpenseSubmitConcurrencyIT,
 * PayrollSubmitConcurrencyIT, and EmployeeUpdateConcurrencyIT, so it shares
 * their cached Spring context under failsafe's default reuseForks=true
 * single fork rather than starting a second one: keeping this as its own
 * named test costs essentially nothing on top of what those three already
 * pay. Its assertion is otherwise fully redundant with theirs — the same
 * full bean graph would already fail to start for any of them if it failed
 * here — but keeping one conventionally-named "does the app boot" test is
 * worth that near-zero cost as a canary and as the obvious first place a
 * new contributor looks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class WmpApplicationIT extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
