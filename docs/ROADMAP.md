# Workforce Management Portal — Build Roadmap

**Stack:** Java 21, Spring Boot 4.1.x, PostgreSQL 16, Flyway, HikariCP, Micrometer/Actuator, React 18 + Vite, Docker Compose, GitHub Actions.

**Repo:** `workforce-management-portal` (monorepo: `/backend`, `/frontend`, `/docs`, `/load`)

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

## Phase 2 — Auth + centralized RBAC filter

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

## Phase 6 — Scale, then keyset pagination

- [ ] Seed generator: 60,000+ expense reports, ~2,000 employees, realistic status/date spread (`/load/seed.sql` or a `@Profile("seed")` runner)
- [ ] **First** implement `/expenses/approvals` with classic offset pagination (`LIMIT ? OFFSET ?`)
- [ ] Measure p50/p95 at page 1, 100, 500, 1000. `EXPLAIN (ANALYZE, BUFFERS)` each. Save output.
- [ ] **Then** rewrite as keyset: `WHERE (submitted_at, id) < (?, ?) ORDER BY submitted_at DESC, id DESC LIMIT ?`
- [ ] Opaque base64 cursor in the response envelope (`nextCursor`), never raw offsets
- [ ] Re-measure the same pages. Table the before/after.
- [ ] `/docs/adr/0002-keyset-pagination.md` — including the trade-off you actually accepted (no random page jumps, harder filtering)

---

## Phase 7 — Actuator timers, then composite indexes

- [ ] Micrometer `@Timed` on approval endpoints; expose `/actuator/metrics/http.server.requests`
- [ ] Optional: Prometheus + Grafana in Compose for a screenshot-able dashboard
- [ ] Baseline the approval path under load (k6 or JMeter, fixed script in `/load/`) — **write down the p50**
- [ ] `EXPLAIN ANALYZE` the slow query → identify the seq scans
- [ ] Add composite indexes (e.g. `(status, approver_id, submitted_at DESC)`, partial index on pending rows)
- [ ] Re-run the identical load script — **write down the new p50**
- [ ] `/docs/measurements.md` with both runs, index DDL, and plans

---

## Phase 8 — Reproduce and fix pool exhaustion

This is the best interview story in the project. Build the bug on purpose, then debug it as if you hadn't.

- [ ] Add a `@Scheduled` nightly batch job (payroll accrual) that opens connections via raw `DataSource.getConnection()` and **leaks them** (no try-with-resources) on a subset of iterations
- [ ] Run the batch while hitting `POST /payroll/runs/{id}/submit` → observe intermittent 500s
- [ ] Diagnose properly: Hikari metrics (`hikaricp.connections.pending`, `.active`), `leakDetectionThreshold`, and `SELECT * FROM pg_stat_activity WHERE state='idle in transaction'`
- [ ] Capture the failing logs — `HikariPool-1 - Connection is not available, request timed out after 30000ms`
- [ ] Fix: try-with-resources, bounded `maximum-pool-size`, `connection-timeout`, `leak-detection-threshold`, and Postgres `statement_timeout` + `idle_in_transaction_session_timeout`
- [ ] Regression test: integration test that fails on the leaky version and passes after
- [ ] `/docs/incidents/2026-xx-payroll-500s.md` — timeline, symptom, hypotheses ruled out, root cause, fix, prevention

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
