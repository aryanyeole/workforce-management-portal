# CLAUDE.md — Workforce Management Portal

Context for Claude Code. Read `ROADMAP.md` for the phase plan before starting work.

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
7. **Every connection/statement is closed.** Try-with-resources everywhere except the deliberately-leaky batch job in Phase 8, which is marked with a `// INTENTIONAL LEAK — see docs/incidents/` comment until it's fixed.
8. **Secrets come from env vars.** Nothing real in `application.yml`.

## Working style

- Work phase by phase; don't jump ahead to a later phase's concern.
- Small commits, Conventional Commits format, imperative mood.
- When a phase involves measurement, run the measurement and paste real output into `/docs/measurements.md`. Never invent a number.
- Prefer boring, explicit code over clever abstractions. This code will be read by interviewers.
- Ask before adding a dependency that isn't already in the stack list.
- Do ONE numbered step from ROADMAP.md at a time. Stop and report when it is done.
- Never start the next step without explicit approval, even if the next step is obvious.
- Never edit ROADMAP.md or CLAUDE.md without asking first.
- Never run `docker compose up`, start services, or modify machine state without asking.
- If you hit an unexpected condition (port conflict, version mismatch, failing test),
  stop and report it. Do not work around it.

## Environment

Windows, VS Code, PowerShell terminal. Give commands in PowerShell syntax. Projects live under `C:\Users\Aryan\Projects\`.

- JAVA_HOME must point to C:\Program Files\Java\jdk-21. A JDK 8 is also
  installed on this machine; if a build fails with "class file has wrong
  version 61.0, should be 52.0", JAVA_HOME has reverted to JDK 8.
- Always build with the Maven Wrapper (.\mvnw.cmd). No standalone mvn on PATH.

## Commands

```powershell
docker compose up -d db          # start Postgres
cd backend; mvn spring-boot:run  # run API
mvn verify                       # tests incl. Testcontainers
cd frontend; npm run dev         # run UI
```
