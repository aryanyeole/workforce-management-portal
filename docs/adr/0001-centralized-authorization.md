# ADR 0001: Centralized route authorization via a single filter + declarative registry

**Status:** Accepted
**Date:** 2026-08-22
**Phase:** 2 (Auth + centralized RBAC filter)

## Context

The API will eventually expose 30 domain endpoints across three business
domains (expense, onboarding, payroll), each requiring a different
combination of allowed roles. The obvious default in a Spring Security app
is `@PreAuthorize("hasRole('MANAGER')")` on individual controller methods,
or `.requestMatchers(...).hasRole(...)` chains in `SecurityFilterChain`.

Both put the authorization rule for a route in a different place than the
rule for the next route, decided by whoever last touched that controller.
For a resume bullet that specifically claims "one authorization component,
not scattered `@PreAuthorize`", and for a codebase interviewers will read,
that scatter is the thing to avoid on purpose.

## Decision

Every protected route is declared exactly once, in one place:
[`PermissionRegistry.declare()`](../../backend/src/main/java/com/aryanyeole/wmp/common/security/PermissionRegistry.java).
Each entry is a `RoutePermission(method, pathPattern, allowedRoles,
ownershipScoped)`. A single servlet filter,
[`RouteAuthorizationFilter`](../../backend/src/main/java/com/aryanyeole/wmp/common/security/RouteAuthorizationFilter.java),
resolves the matching rule for every incoming request and either lets it
through or writes a `ProblemDetail` 401/403 itself — before the
`DispatcherServlet`, before any controller method runs.

Two filters divide the work along a clean seam:

- `JwtAuthenticationFilter` only *authenticates*: it turns a valid Bearer
  token into a populated `SecurityContext`, or leaves the request
  anonymous. It never makes an authorization decision.
- `RouteAuthorizationFilter` only *authorizes*: given whatever principal
  (or lack of one) is in the context, it looks up the route in
  `PermissionRegistry` and allows or denies.

Controllers carry no `@PreAuthorize`, no `.hasRole(...)` matchers, and
services carry no `if (principal.role() != ...)` checks. `SecurityConfig`
deliberately leaves Spring's own `authorizeHttpRequests` permissive
(`anyRequest().permitAll()`) — see the comment there — so there is exactly
one place a reviewer needs to open to answer "who can call this endpoint."

### Route-level RBAC vs. row-level ownership

`RoutePermission` carries an `ownershipScoped` flag. When true (e.g. `GET
/api/v1/expenses`, `GET /api/v1/expenses/{id}`), the filter's role check is
necessary but not sufficient: an `EMPLOYEE` may call the route, but must
only ever see their own expense reports, never a coworker's.

That second check is deliberately **not** done in the filter. The filter
sees a method and a path; it does not — and structurally cannot, without
hitting the database — know that `/api/v1/expenses/482` belongs to
employee 17 and not employee 9. Pushing ownership into the filter would
mean parsing path/query params for entity IDs and running an extra query
per request, in a component whose entire value is being simple enough to
audit at a glance.

Instead, ownership is enforced where the data lives: as a `WHERE
employee_id = :callerId` (or `WHERE approver_id = :callerId` for manager
approvals) baked into the repository query itself, once `AuthPrincipal` is
available in the service layer. This is still "one place" per resource —
every query against `expense_reports` for a non-privileged caller goes
through the same scoped repository method — it is just a different one
place than the route-level filter, because it is answering a different
question ("which rows" vs. "which roles").

The `ownershipScoped` flag on `RoutePermission` exists so this split is
visible in the registry itself: a reviewer reading `declare()` can see at
a glance which routes need that second layer and cannot forget to add it,
even though the flag itself enforces nothing.

### Deny-by-default for unregistered routes

`RouteAuthorizationFilter.resolve()` returns `Optional<RoutePermission>`.
An empty result — no rule matches the method+path — is treated as a 403,
not a pass-through. Concretely: forgetting to add a new controller's
routes to `PermissionRegistry` fails loudly and immediately (every request
to it 403s, and a warning is logged: "Denied unregistered route"), rather
than shipping a route with no access control at all. The alternative
(default-allow) fails silently — the bug is invisible until someone
notices sensitive data leaking.

The cost is friction: every new endpoint requires a `PermissionRegistry`
edit before it does anything, including in local manual testing. That
friction is the point — it is the mechanism that makes "you can't forget"
true rather than aspirational.

### 403 vs. 404

This ADR does not yet settle the general 403-vs-404 leak-safety question
(roadmap Phase 2 calls for a decision "documented"; the current behavior
is closer to "return whatever RouteAuthorizationFilter or the missing
controller happens to produce" — 403 for a role denied by the registry,
404 for a route the registry allows but no controller yet answers). That
policy — e.g., whether `GET /api/v1/expenses/{id}` should 404 rather than
403 when the id exists but belongs to someone else, to avoid confirming
the id is valid — needs revisiting once the expense/onboarding/payroll
controllers exist in Phases 3–5, since it depends on service-layer
behavior this phase does not build yet.

### Declaration order is significant

`PermissionRegistry.resolve()` uses `rules.stream().filter(...).findFirst()`
— the **first** matching rule wins, in the order `declare()` lists them,
not the most specific one. `PathPattern` alone cannot tell you that
`/api/v1/expenses/categories` should win over `/api/v1/expenses/{id}`;
both match the literal path `/api/v1/expenses/categories`. `declare()`
handles this by listing literal segments (`/categories`, `/approvals`,
`/auth/me`) before the `{id}` wildcard route they would otherwise be
swallowed by.

This is a real footgun: adding a new literal route to the *bottom* of the
list, after an existing wildcard that also matches it, silently applies
the wrong rule with no compiler or runtime error. There is no test today
that would catch a mis-ordered addition (`RouteAuthorizationIT`'s table
happens to test only the current, correctly-ordered registry). The
mitigating factor is that `declare()` is one method, short, and grouped by
domain with a comment noting the convention — but this is a trade-off
accepted knowingly, not a solved problem.

### Role as a JWT claim, and its staleness window

`AuthPrincipal.role()` comes from the `role` claim baked into the access
token at login/refresh time (`JwtService.issue`), not looked up fresh from
`user_accounts` on every request. This is what makes the filter cheap
enough to run on every request without a database round trip.

The cost: if an admin changes a user's role (or deactivates their
account), that change does not take effect until the user's current
access token expires — up to `wmp.jwt.access-token-ttl` (30 minutes) — or
they explicitly refresh. `AuthService.refresh()` does re-check
`is_active` against the database on every refresh (see
[`AuthService`](../../backend/src/main/java/com/aryanyeole/wmp/auth/service/AuthService.java)),
so a disabled account cannot keep refreshing forever, but it can keep
using an *already-issued* access token for up to 30 minutes after being
disabled. A role change (not a deactivation) has no forced-refresh
mechanism at all yet — the old role claim is valid until natural
expiry. Shortening the access-token TTL narrows this window but does not
close it; closing it fully would mean either a server-side revocation
list (a stateful check on every request — the exact cost this design
exists to avoid) or accepting the staleness as a documented trade-off.
This phase accepts it.

### `typ` claim: refresh tokens cannot be used as access tokens

Both access and refresh tokens are signed with the same key and carry the
same claim shape, differing only in `typ` (`"access"` vs `"refresh"`) and
TTL. `JwtService.parseAccessToken` rejects any token where `typ !=
"access"`; the mirrored `parseRefreshToken` rejects any token where `typ
!= "refresh"`. Without this, a refresh token — which lives for 7 days
instead of 30 minutes — could be used directly as a Bearer access token,
turning a token meant only to mint new access tokens into a much
longer-lived credential for calling the API directly. `RouteAuthorizationIT`
asserts this specifically: a refresh token sent as a Bearer token against
`/api/v1/auth/me` gets 401.

## Consequences

**What this buys:**
- One file (`PermissionRegistry`) answers "who can call this route" for
  the entire API. Adding an endpoint means adding a `RoutePermission` line
  — no controller annotation, no service-layer role check.
- Forgetting that line fails closed (403), not open.
- The authorization decision is unit-testable in isolation from any
  specific controller — `RouteAuthorizationIT`'s table exercises the
  registry against real routes without needing every domain controller to
  exist yet.

**What this makes harder:**
- Ownership/ambient checks ("can this MANAGER approve *this specific*
  report") are invisible from `PermissionRegistry` alone — you have to
  also read the repository/service layer, and the `ownershipScoped` flag
  is only a pointer to "look elsewhere," not enforcement.
- Declaration order in `declare()` is load-bearing and unchecked by the
  compiler; a careless append can silently shadow an existing rule.
- Role changes and deactivations have a staleness window bounded by
  access-token TTL, not immediate — this is a deliberate availability/
  simplicity trade against a stateful revocation mechanism this phase
  does not build.
- `@PreAuthorize` is a well-known, IDE-navigable Spring idiom;
  `PermissionRegistry` is bespoke. A new contributor has to learn this
  file's convention rather than recognizing a framework annotation — the
  trade this ADR is making is that a single 100-line file is easier to
  audit completely than 30 scattered annotations, even at the cost of
  being less immediately familiar.
