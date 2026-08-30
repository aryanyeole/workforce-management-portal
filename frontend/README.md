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

As of Phase 9 Task 1, the backend does not yet accept cross-origin requests
from this app's dev server — see that task's report for the preflight
evidence and the proposed fix (a single, dev-profile-gated CORS
configuration bean on the backend, not yet implemented pending approval).
Until that lands, requests from `npm run dev` will fail in a real browser
even though the backend itself is reachable.

## Other commands

```powershell
npm run build     # type-check (tsc -b) then production build to dist/
npm run preview   # serve the production build locally
```
