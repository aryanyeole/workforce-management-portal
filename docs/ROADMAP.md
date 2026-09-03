# Workforce Management Portal — Build Roadmap

**Goal:** a real, running system that makes every claim on the resume literally true — including the measured numbers.

**Stack:** Java 21, Spring Boot 4.1.x, PostgreSQL 16, Flyway, HikariCP, Micrometer/Actuator, React 18 + Vite, Docker Compose, GitHub Actions.

**Repo:** `workforce-management-portal` (monorepo: `/backend`, `/frontend`, `/docs`, `/load`)

---

## Ground rule: measure before you fix

Three resume bullets describe *outcomes of investigation*, not features:

- "Chose keyset pagination **once the table crossed 50,000 rows**"
- "**Traced** intermittent 500s to connection-pool exhaustion"
- "Cut latency **from 410ms to 120ms**"

These only become true if the broken state exists first. So Phases 6–8 deliberately build the slow/broken version, measure it, then fix it. Record every number in `/docs/measurements.md` with the raw Actuator output. Your real numbers will differ from 410→120 — **update the resume to your actual numbers when you're done.**

---

## Phase 0 — Skeleton & first commit

- [ ] Verify toolchain: JDK 21, Maven, Docker Desktop, Node 20+
- [ ] `git init`, GitHub repo `workforce-management-portal`, MIT license, `.gitignore`
- [ ] Spring Boot skeleton via start.spring.io (web, data-jpa, validation, security, actuator, flyway, postgresql, lombok)
- [ ] `docker-compose.yml` with Postgres 16 + named volume
- [ ] `GET /actuator/health` returns UP against the containerized DB
- [ ] `README.md` stub

**Done when:** `mvn spring-boot:run` connects to Dockerized Postgres and health is UP.

---

## Phase 1 — Domain model & schema

Tables: `employees`, `departments`, `roles`, `user_accounts`, `onboarding_tasks`, `onboarding_documents`, `payroll_runs`, `payroll_items`, `expense_reports`, `expense_categories`, `approval_events`, `audit_log`.

- [ ] Flyway migrations `V1__baseline.sql` … (never edit an applied migration — add a new one)
- [ ] JPA entities + repositories
- [ ] Enums as Postgres `text` + CHECK constraints (not `ordinal`)
- [ ] `created_at` / `updated_at` on everything; UUID v7-ish or BIGSERIAL PKs
- [ ] Testcontainers wired up so integration tests run on real Postgres

**Done when:** `mvn verify` spins a Testcontainer, applies migrations, passes a repository smoke test.

---

## Phase 2 — Auth + centralized RBAC filter ← *resume bullet 1*

The whole point: **one** authorization component, not `@PreAuthorize` scattered across 30 controllers.

- [ ] JWT issue/verify (`/api/v1/auth/login`, `/refresh`, `/me` — these are *outside* the 30)
- [ ] Roles: `EMPLOYEE`, `MANAGER`, `PAYROLL_ADMIN`, `HR_ADMIN`, `SYSTEM`
- [ ] A `OncePerRequestFilter` that resolves the principal, loads roles, and evaluates a declarative permission map (route pattern + method → required role/scope) before the controller is reached
- [ ] Ownership rules ("employee can read only their own payslips") handled in one place, not per-controller
- [ ] 403 vs 404 policy decided and documented (leak-safe: 404 for resources you can't see)
- [ ] Tests: matrix of role × endpoint asserting 200/403

**Done when:** you can add a new endpoint to the permission map and it's protected without touching the controller. Write this up in `/docs/adr/0001-centralized-authorization.md`.

---

## Phases 3–5 — The 30 endpoints (all `/api/v1/...`)

Build one domain per phase, each with: DTOs (never expose entities), bean validation, RFC 7807 `ProblemDetail` errors, service layer, integration tests.

### Expense (Phase 3) — 10
| # | Method | Path |
|---|--------|------|
| 1 | POST | `/expenses` |
| 2 | GET | `/expenses` |
| 3 | GET | `/expenses/{id}` |
| 4 | PATCH | `/expenses/{id}` |
| 5 | DELETE | `/expenses/{id}` |
| 6 | POST | `/expenses/{id}/submit` |
| 7 | POST | `/expenses/{id}/approve` |
| 8 | POST | `/expenses/{id}/reject` |
| 9 | GET | `/expenses/approvals` ← **the 50k-row queue** |
| 10 | GET | `/expenses/categories` |

### Onboarding (Phase 4) — 10
| # | Method | Path |
|---|--------|------|
| 11 | POST | `/onboarding/employees` |
| 12 | GET | `/onboarding/employees` |
| 13 | GET | `/onboarding/employees/{id}` |
| 14 | PATCH | `/onboarding/employees/{id}` |
| 15 | DELETE | `/onboarding/employees/{id}` (soft delete) |
| 16 | GET | `/onboarding/employees/{id}/tasks` |
| 17 | POST | `/onboarding/employees/{id}/tasks` |
| 18 | PATCH | `/onboarding/tasks/{taskId}` |
| 19 | POST | `/onboarding/employees/{id}/documents` |
| 20 | GET | `/onboarding/employees/{id}/documents` |

### Payroll (Phase 5) — 10
| # | Method | Path |
|---|--------|------|
| 21 | POST | `/payroll/runs` |
| 22 | GET | `/payroll/runs` |
| 23 | GET | `/payroll/runs/{id}` |
| 24 | POST | `/payroll/runs/{id}/submit` ← the endpoint that 500s in Phase 8 |
| 25 | POST | `/payroll/runs/{id}/approve` |
| 26 | POST | `/payroll/runs/{id}/reject` |
| 27 | GET | `/payroll/runs/{id}/items` |
| 28 | POST | `/payroll/runs/{id}/items` |
| 29 | GET | `/payroll/employees/{employeeId}/payslips` |
| 30 | GET | `/payroll/summary` |

- [ ] springdoc-openapi so `/swagger-ui.html` proves the count
- [ ] State machines enforced (DRAFT → SUBMITTED → APPROVED/REJECTED); illegal transitions → 409

**Done when:** OpenAPI lists exactly 30 domain endpoints and every one has an integration test.

---

## Phase 6 — Scale, then keyset pagination ← *resume bullet 2*

- [ ] Seed generator: 60,000+ expense reports, ~2,000 employees, realistic status/date spread (`/load/seed.sql` or a `@Profile("seed")` runner)
- [ ] **First** implement `/expenses/approvals` with classic offset pagination (`LIMIT ? OFFSET ?`)
- [ ] Measure p50/p95 at page 1, 100, 500, 1000. `EXPLAIN (ANALYZE, BUFFERS)` each. Save output.
- [ ] **Then** rewrite as keyset: `WHERE (submitted_at, id) < (?, ?) ORDER BY submitted_at DESC, id DESC LIMIT ?`
- [ ] Opaque base64 cursor in the response envelope (`nextCursor`), never raw offsets
- [ ] Re-measure the same pages. Table the before/after.
- [ ] `/docs/adr/0002-keyset-pagination.md` — including the trade-off you actually accepted (no random page jumps, harder filtering)

**Done when:** deep-page latency is flat and you have the two `EXPLAIN` plans side by side.

---

## Phase 7 — Actuator timers, then composite indexes ← *resume bullet 4*

- [ ] Micrometer `@Timed` on approval endpoints; expose `/actuator/metrics/http.server.requests`
- [ ] Optional: Prometheus + Grafana in Compose for a screenshot-able dashboard
- [ ] Baseline the approval path under load (k6 or JMeter, fixed script in `/load/`) — **write down the p50**
- [ ] `EXPLAIN ANALYZE` the slow query → identify the seq scans
- [ ] Add composite indexes (e.g. `(status, approver_id, submitted_at DESC)`, partial index on pending rows)
- [ ] Re-run the identical load script — **write down the new p50**
- [ ] `/docs/measurements.md` with both runs, index DDL, and plans

**Done when:** you can state your real before/after numbers and show the plan that explains them.

---

## Phase 8 — Reproduce and fix pool exhaustion ← *resume bullet 3*

This is the best interview story in the project. Build the bug on purpose, then debug it as if you hadn't.

- [ ] Add a `@Scheduled` nightly batch job (payroll accrual) that opens connections via raw `DataSource.getConnection()` and **leaks them** (no try-with-resources) on a subset of iterations
- [ ] Run the batch while hitting `POST /payroll/runs/{id}/submit` → observe intermittent 500s
- [ ] Diagnose properly: Hikari metrics (`hikaricp.connections.pending`, `.active`), `leakDetectionThreshold`, and `SELECT * FROM pg_stat_activity WHERE state='idle in transaction'`
- [ ] Capture the failing logs — `HikariPool-1 - Connection is not available, request timed out after 30000ms`
- [ ] Fix: try-with-resources, bounded `maximum-pool-size`, `connection-timeout`, `leak-detection-threshold`, and Postgres `statement_timeout` + `idle_in_transaction_session_timeout`
- [ ] Regression test: integration test that fails on the leaky version and passes after
- [ ] `/docs/incidents/2026-xx-payroll-500s.md` — timeline, symptom, hypotheses ruled out, root cause, fix, prevention

**Done when:** the incident doc reads like a real postmortem and the regression test guards it.

---

## Phase 9 — React approvals UI

- [ ] Vite + React 18 + TypeScript, TanStack Query
- [ ] Login, token storage, role-aware nav
- [ ] Approvals table over the 50k rows with cursor-based infinite scroll (this is *why* keyset exists)
- [ ] Bulk approve/reject, optimistic updates, error toasts on 409
- [ ] Expense submit form + onboarding checklist views

**Done when:** you can scroll to row 40,000 without the UI degrading.

---

## Phase 10 — Production polish

- [ ] Testcontainers integration suite; target ~70%+ meaningful coverage on service layer
- [ ] GitHub Actions: build, test, Docker image build on every push
- [ ] Multi-stage Dockerfiles; single `docker compose up` runs db + api + web
- [ ] Structured JSON logging with request/correlation IDs
- [ ] Rate limiting + CORS + secrets via env, not committed

---

## Phase 11 — Make it legible to employers

- [ ] `README.md`: architecture diagram, one-command quickstart, the **before/after tables**, screenshots
- [ ] ADRs 0001–0003 and the incident doc linked from the README
- [ ] Get a real code review — an ASU peer, a mentor, or a public PR — so "confirmed during peer code review" is true. If you can't, reword that clause.
- [ ] Rewrite the four resume bullets using your *measured* numbers
- [ ] Technical blog post on the pool-exhaustion debug (best-performing kind of post for backend roles)

---

## Commit discipline

Interviewers do read git history — and at least one of your take-homes is graded on it.

- Small, atomic commits with imperative subjects: `feat(expense): add keyset pagination to approvals queue`
- Conventional Commits prefixes: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `chore`
- Branch per phase: `phase-6-keyset-pagination` → PR → squash or merge
- Commit the *slow* version before the fast one. The history itself becomes evidence of the engineering narrative.
