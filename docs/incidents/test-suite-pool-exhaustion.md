# Incident: intermittent HikariPool failures in the IT suite

**Date:** 2026-08-23
**Status:** Resolved
**Affected:** `.\mvnw.cmd verify` (Failsafe integration-test phase only)

## Symptom

Running `.\mvnw.cmd clean verify` with default Surefire/Failsafe settings
failed intermittently — but reproducibly, at the same point in the class
sequence — with:

```
org.springframework.dao.DataAccessResourceFailureException: Unable to
acquire JDBC Connection [HikariPool-1 - Connection is not available,
request timed out after 30009ms (total=0, active=0, idle=0, waiting=0)]
```

`RouteAuthorizationIT` and `ExpenseLifecycleIT` failed at `@BeforeAll`
(the first repository call in each). `OpenApiIT` (which runs first) and
`EmployeeRepositoryIT` (which runs last) always passed. Running the exact
same suite with `-DreuseForks=false` (a fresh JVM per test class) always
passed.

`total=0` was the load-bearing detail: not "pool exhausted from being
fully used" (which would show `active`/`idle` > 0), but "the pool has
zero live connections and can't establish new ones" — i.e. the server it
was pointed at wasn't answering, not "too many clients."

## What was measured

Four consecutive `mvn clean verify` runs, one before the fix and three
(plus a fourth confirmation) after, all with default settings (no fork
flags):

**Before the fix**, in one full run:

- `grep -c "Creating container for image: postgres"` → **4** — one new
  Postgres Testcontainer per `@SpringBootTest`/`@DataJpaTest` class
  (`OpenApiIT`, `RouteAuthorizationIT`, `ExpenseLifecycleIT`,
  `EmployeeRepositoryIT`), each with a different container ID and a
  different mapped port.
- Spring context count: **3** distinct contexts, not 4. `OpenApiIT`,
  `RouteAuthorizationIT`, and `ExpenseLifecycleIT` share identical
  `@SpringBootTest(webEnvironment = MOCK) @AutoConfigureMockMvc`
  configuration, so Spring's context cache correctly reused **one**
  context across all three (confirmed by the absence of a fresh
  "Starting ClassName using Java 21..." banner for the second and third).
  `EmployeeRepositoryIT`'s `@DataJpaTest` is a different bootstrapper, so
  it got its own, second, context.
- `SHOW max_connections` on the live container: **100** (Postgres
  default; nothing in this project changes it).
- `pg_stat_activity` grouped by `application_name`/`state`, sampled while
  the first container was still up: **16 total** connections — 10 `idle`
  from `PostgreSQL JDBC Driver` (Hikari's pool, fully opened), 5
  background/system backends, 1 `psql` (the diagnostic session itself).
- No `application-test.yml`/test profile exists in this project, so
  Hikari runs on its own built-in default, **`maximumPoolSize = 10`**
  (confirmed by exactly 10 repeated `Failed to validate connection`
  warnings at the moment of failure — one per pooled connection).

**After the fix**, four consecutive runs: `grep -c "Creating container"`
→ **1** every time; **54/54** tests passing every time.

## What was ruled out

**Connection-count exhaustion.** 10 (Hikari's default pool size) × 2 (the
two Spring contexts that ever touch the Testcontainers Postgres) = 20,
nowhere near `max_connections = 100`. The 16-connection sample above
confirms this directly. Raising `max_connections` or lowering
`maximum-pool-size` would not have changed the outcome, since the
problem was never about the number of connections a live server could
accept.

**Spring's context cache "not working."** It was working exactly as
designed — that's what made this confusing at first. Three IT classes
with identical configuration correctly shared one cached
`ApplicationContext`. The bug was that the *container* underneath that
correctly-cached context's `DataSource` bean did not stay alive for as
long as the context did.

## Root cause

`AbstractIntegrationTest` declared its shared Postgres container as:

```java
@Testcontainers
public abstract class AbstractIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
```

This looks like a shared singleton (`static final`, one field, one common
base class), and its own javadoc claimed exactly that. But per
Testcontainers' own documentation ("Singleton containers", under Manual
Container Lifecycle Control): *"There is no special support for this use
case provided by the Testcontainers extension"* — the `@Testcontainers`
JUnit5 extension only guarantees a static `@Container` field is reused
across `@Test` methods **within one class**. Under Maven Failsafe running
all IT classes in one reused JVM fork, the extension stopped the
container at the end of each class and started a fresh one for the next
— evidenced directly by the four different container IDs and four
`Creating container` log lines, all within a single, uninterrupted JVM
process (same PID throughout).

Spring's context cache, meanwhile, had no way to know the container
underneath its cached context had been swapped out: the `DataSource`
bean's JDBC URL is resolved once, at context-creation time, from
whatever port the container reported *then*. `OpenApiIT` built that
context against its own container's port. By the time
`RouteAuthorizationIT` reused the cached context, that container was
already gone, replaced by a new one on a new port the old `DataSource`
had never heard of. Hikari's 10 pooled connections died silently
(`This connection has been closed`); its attempt to open replacements
targeted the same dead port and got nothing back — hence `total=0`,
followed by the 30-second `connection-timeout` default before Hikari
gave up. `EmployeeRepositoryIT` survived only because its `@DataJpaTest`
slice is different enough to force Spring to build a genuinely fresh
context, which resolved `@ServiceConnection` fresh, against whatever
container happened to be live at that later moment.

## Fix

`AbstractIntegrationTest` now starts the container in a plain static
initializer block instead of via `@Testcontainers`/`@Container`:

```java
public abstract class AbstractIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES;
    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
    }
}
```

This is Testcontainers' own documented "singleton container" pattern: the
container starts exactly once, the first time any subclass triggers
`AbstractIntegrationTest`'s class initialization, and — critically —
nothing ever calls `.stop()` on it mid-suite, because no JUnit extension
is registered to do so anymore. Ryuk (started automatically by
Testcontainers core the first time any container starts, independent of
the JUnit5 `@Testcontainers` extension) still reaps it when the JVM
exits. `@ServiceConnection` is unaffected by removing `@Testcontainers`/
`@Container`: it's wired up by Spring's own
`ServiceConnectionContextCustomizerFactory`, which scans the test class
hierarchy for the annotation directly — it does not depend on the
Testcontainers JUnit extension being active.

Verified with four consecutive `.\mvnw.cmd clean verify` runs at default
settings: 54/54 tests, `BUILD SUCCESS` every time, exactly one container
created per run. As a side effect, the suite got substantially faster —
`RouteAuthorizationIT` dropped from ~33s to ~7s and `ExpenseLifecycleIT`
from ~40s to ~2s, since they now reuse a warm container and context
instead of each waiting out a fresh container start and Flyway migration.

## Why `-DreuseForks=false` was rejected as the fix

It does make the symptom disappear: a fresh JVM per test class means a
fresh classloader, so `AbstractIntegrationTest`'s static field
re-initializes (and a fresh container starts) for every class, and no
class ever inherits another class's now-stale `DataSource`. But that's
papering over the mechanism, not fixing it — it would have quietly
reintroduced "one container per class" as permanent behavior (the
opposite of the intended shared-container design), paid for a full
JVM+Spring+Flyway bootstrap on every IT class going forward, and given
no indication to a future reader of `pom.xml` about *why* forks can't be
reused. The actual defect was that the container's lifecycle didn't match
its intended scope; fixing that directly also happens to make the suite
faster, not just quieter.
