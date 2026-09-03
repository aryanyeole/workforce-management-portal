# CONVENTIONS.md — Workforce Management Portal

## What this project is

A workforce management REST API covering three domains — payroll, onboarding, expenses — with a React approvals UI. It is a portfolio project built to be genuinely production-shaped, not a tutorial CRUD app. The performance and reliability work (Phases 6–8) is the point, not a footnote.

## Stack

- Java 21, Spring Boot 4.1.x, Maven
- PostgreSQL 16, Flyway migrations, HikariCP
- Spring Security (JWT), Micrometer + Actuator
- React 18 + TypeScript + Vite + TanStack Query
- Docker Compose, Testcontainers, GitHub Actions

## Layout

```
/backend    Spring Boot API
/frontend   React app
/docs       ADRs, incident reports, measurements
/load       seed data + k6 load scripts
```

Backend packages: `com.aryanyeole.wmp.<domain>` where domain is `auth`, `payroll`, `onboarding`, `expense`, `common`. Within a domain: `api` (controllers, DTOs), `domain` (entities, enums), `repository`, `service`.

## Non-negotiable conventions

1. **Authorization lives in one place.** A single filter + declarative permission map. Do not add `@PreAuthorize` to controllers or inline role checks in services. If a new rule doesn't fit the map, change the map's design — don't work around it.
2. **Controllers never see entities.** Request/response DTOs only. Map explicitly; no reflection-based auto-mapping magic.
3. **Errors are RFC 7807 `ProblemDetail`.** One `@RestControllerAdvice`. No stack traces in responses.
4. **Migrations are append-only.** Never edit an applied Flyway file.
5. **Every endpoint gets an integration test** against Testcontainers Postgres, including a 403 case.
6. **No `LIMIT/OFFSET` on the approvals queue** after Phase 6. Keyset only, opaque cursors.
7. **Every connection/statement is closed.** Try-with-resources everywhere, including batch jobs that acquire connections via raw `DataSource.getConnection()`. See `docs/incidents/2026-08-payroll-500s.md` for what happens when they aren't.
8. **Secrets come from env vars.** Nothing real in `application.yml`.

## Commands

```powershell
docker compose up -d db          # start Postgres
cd backend; mvn spring-boot:run  # run API
mvn verify                       # tests incl. Testcontainers
cd frontend; npm run dev         # run UI
```
