# syntax=docker/dockerfile:1

# ---- Build stage ------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# Manifest files copied and installed before source: as long as
# package.json/package-lock.json don't change, Docker reuses this layer's
# cache regardless of what changes under src/ -- same reasoning as the
# backend Dockerfile's dependency:go-offline layer.
COPY package.json package-lock.json ./

# npm ci, not npm install: ci requires an existing lockfile, deletes
# node_modules first, and installs exactly what package-lock.json specifies
# -- deterministic and reproducible, and it fails loudly if package.json and
# the lockfile disagree rather than silently rewriting the lockfile to
# reconcile them (which is what install would do). It's also faster here,
# since it skips install's extra dependency-resolution work in favor of
# just laying out what the lockfile already resolved. install is the right
# tool for a developer adding/updating a dependency; ci is the right tool
# for a build that must reproduce a committed lockfile exactly.
RUN npm ci

COPY . .

# Empty by default, not the dev localhost:8080 default client.ts falls back
# to. client.ts reads this as `import.meta.env.VITE_API_BASE_URL ?? '...'`
# -- ?? only falls back on null/undefined, and a build arg that is set to
# "" is neither, so every fetch call's ${API_BASE_URL}${path} collapses to
# just ${path}: a relative request against whatever origin actually serves
# this build, not one backend baked in forever. The runtime stage's nginx
# config is what turns that relative /api/v1/... path into a real request,
# proxying it to a backend address resolved at container *start*, not here
# -- see nginx.conf.template. Overridable via --build-arg for any case that
# genuinely does want a fixed absolute URL baked into the bundle instead.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build


# ---- Runtime stage ------------------------------------------------------
# nginx, not `npm run dev` and not a Node server: this only ever serves an
# already-built static dist/ -- nothing here executes JavaScript server-side.
FROM nginx:1.27-alpine AS runtime

COPY --from=build /app/dist /usr/share/nginx/html

# Templated, not a plain .conf: nginx's own docker-entrypoint.sh runs
# envsubst over every *.template file under /etc/nginx/templates/ at
# container start, writing the result to /etc/nginx/conf.d/ with the
# .template suffix stripped -- substituting only names that are actually
# set as environment variables (BACKEND_HOST, BACKEND_PORT below), so
# nginx's own built-in variables in the same file ($uri, $host, $scheme,
# etc.) are left alone rather than being blanked out by envsubst.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Defaults; override with `docker run -e BACKEND_HOST=... -e BACKEND_PORT=...`
# to point the same image at a different backend without a rebuild.
ENV BACKEND_HOST=backend
ENV BACKEND_PORT=8080

EXPOSE 80
