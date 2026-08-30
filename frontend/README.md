# Workforce Management Portal — frontend

React 18 + TypeScript + Vite + TanStack Query. See `/CLAUDE.md` at the repo
root for the full project context; this file only covers running the
frontend itself.

## Running it

```powershell
cd frontend
npm install
npm run dev
```

Opens on **http://localhost:5173** (Vite's default). The backend API must
already be running separately — see the repo root `CLAUDE.md` for how to
start it (`docker compose up -d db` then `cd backend; mvn spring-boot:run`).

## API URL

The app expects the API at **http://localhost:8080** by default (matching
the backend's own default port). To point at a different URL, copy
`.env.example` to `.env` and set `VITE_API_BASE_URL`:

```
VITE_API_BASE_URL=http://localhost:8080
```

See `src/api/client.ts` for where this is read.

## CORS

Resolved in Phase 9 Task 1b: the backend has a single, dev-profile-gated
CORS configuration bean (`backend/.../common/config/CorsConfig.java`),
origin read from `wmp.cors.allowed-origin` (defaults to
`http://localhost:5173`, this app's own dev port). It only exists when the
backend runs with `--spring-boot.run.profiles=dev` — see that task's report
for the verification that it's genuinely absent otherwise, and for how the
preflight is actually handled (Spring Security's `CorsFilter` short-circuits
the request before it reaches this app's own auth filters — see
`docs/incidents/` if that report gets archived there later).

## Linting

**Not configured.** `create-vite`'s template included `oxlint`, which was
deliberately dropped in Phase 9 Task 1 as out of scope (not on CLAUDE.md's
pre-approved dependency list, and linting wasn't part of that task).
Phase 10's CI work should treat this as "needs to be added," not assume a
lint step already exists — there is no `npm run lint` script.

## Other commands

```powershell
npm run build     # type-check (tsc -b) then production build to dist/
npm run preview   # serve the production build locally
```
