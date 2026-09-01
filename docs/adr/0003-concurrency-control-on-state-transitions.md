# ADR 0003: Concurrency control on state transitions

**Status:** Accepted
**Date:** 2026-09-01
**Phase:** 10, Task 0 / Task 0b (found and fixed ahead of Phase 10 proper)

## Context

Three services implement the same lifecycle shape: read an entity, check its
current status against an allowed-transitions map
([`ExpenseTransitions`](../../backend/src/main/java/com/aryanyeole/wmp/expense/service/ExpenseTransitions.java),
[`PayrollTransitions`](../../backend/src/main/java/com/aryanyeole/wmp/payroll/service/PayrollTransitions.java),
[`EmployeeTransitions`](../../backend/src/main/java/com/aryanyeole/wmp/onboarding/service/EmployeeTransitions.java)),
then mutate the status field and let Hibernate's own dirty-checking flush the
write at commit. Nothing between the read and the write stops two concurrent
callers from both passing the check against the same pre-write snapshot.

[`ExpenseService.submit`/`decide`](../../backend/src/main/java/com/aryanyeole/wmp/expense/service/ExpenseService.java)
and
[`PayrollService.submit`/`decide`](../../backend/src/main/java/com/aryanyeole/wmp/payroll/service/PayrollService.java)
have this shape. So does
[`EmployeeService.update`](../../backend/src/main/java/com/aryanyeole/wmp/onboarding/service/EmployeeService.java)'s
`employmentStatus` branch.

### How it was found

The expense path surfaced with a symptom. A rapid double-click on the
frontend's Submit button (Phase 9 Task 4) produced two `approval_events` rows
recording the same `SUBMITTED` transition for one expense report — ids 77 and
78, roughly 6ms apart, confirmed by querying the dev database directly. That
symptom exists because `ExpenseService.decide`/`submit` write an audit-trail
row alongside the status field: two winning transactions mean two audit rows,
and an unexpected row-count change on a table nothing else touches is the
kind of thing that gets noticed.

`PayrollService.submit`/`decide` were checked next on the strength of sharing
the same code shape, not because anything about payroll had misbehaved. The
same deterministic reproduction (see Evidence below) produced the same
result there too.

`EmployeeService.update`'s `employmentStatus` branch was checked for the same
reason — the shape, not a symptom — and it has no equivalent tell. The method
writes no audit-trail row at all, so two concurrent transitions racing the
same target produce no duplicate anything to notice. What happens instead is
quieter: the losing request completes with a `200` describing the *winning*
transition's outcome, not its own. A caller that asked to move an employee to
`ON_LEAVE` and got back `TERMINATED` — because a second caller's request
happened to commit last — has no way to tell from the response that its own
request didn't win. This is stated plainly because it's the reason this ADR
exists: the defect was found by generalizing from a code shape, not by
anyone observing a failure. Assuming `EmployeeService.update` was safe
because nothing had ever been reported broken about it would have been
wrong.

## Decision

### Options considered and rejected

- **`@Version` optimistic locking.** The idiomatic JPA answer, and it would
  protect every future write to these entities automatically, not only the
  status field this defect is about. Rejected for exactly that breadth: it
  needs a schema migration (a `version` column on three tables), a new
  `GlobalExceptionHandler` mapping (`ObjectOptimisticLockingFailureException`
  isn't handled today), and it guards every field on the entity rather than
  the one transition actually at risk. Wider than the problem, for a fix
  meant to be surgical.

- **Pessimistic locking (`SELECT ... FOR UPDATE`).** The one option that
  would have let the *existing* `ConflictException`/409 path fire with zero
  new code: the loser's own `requireTransition` check, run against a
  lock-forced fresh read, throws exactly the message it already throws for a
  non-race illegal transition. Rejected anyway, because "a lock-forced fresh
  read" is not what `@Lock` gives you for free on an entity already loaded in
  the persistence context. Hibernate acquires the row lock but does not
  refresh that entity's in-memory field values unless the caller explicitly
  calls `entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` —
  reaching for `@Lock` on a plain `findById` and trusting it to work is the
  natural, wrong first attempt, and it compiles and passes any test that
  never exercises the racing path. Rejected as more fragile than it looks,
  not as incorrect in principle.

- **A uniqueness constraint on `approval_events`.** The direct precedent —
  `PayrollService.createRun`'s duplicate-period guard — pairs a pre-check
  with a caught `DataIntegrityViolationException`, because the pre-check
  alone has a race window and the constraint alone gives a worse error path.
  Considered for the same reason here. Rejected because it doesn't guard the
  actual race target: the status column is still written by two racing
  `UPDATE`s regardless of what constraint sits on a different table. A
  constraint shaped like "one row per `(entity_type, entity_id, action)`"
  would only work as a side effect of this particular transition graph being
  entirely terminal — every action reachable for a given entity recordable
  at most once across that entity's whole lifecycle. That happens to be true
  for `ExpenseStatus` and `PayrollRunStatus`, both strictly one-way. The
  objection — that this stops being a safe assumption the moment any
  transition graph in this codebase gains a cycle — was raised before
  `EmployeeService.update` had been examined at all, on general grounds. It
  was confirmed, not just theorized, once that path was actually looked at:
  `EmploymentStatus` has a real cycle (`ACTIVE <-> ON_LEAVE`), care of
  `EmployeeTransitions`. Employee has no `approval_events` write to begin
  with, so the constraint wouldn't even apply there — but the underlying
  worry, that a uniqueness constraint's correctness depends on the shape of
  the transition graph rather than on the race itself, turned out to be
  exactly the kind of thing that would have quietly stopped holding for a
  case one domain over.

### The chosen guard

A hand-written conditional `UPDATE`, once per affected repository —
[`ExpenseReportRepository.compareAndSetStatus`](../../backend/src/main/java/com/aryanyeole/wmp/expense/repository/ExpenseReportRepository.java),
[`PayrollRunRepository.compareAndSetStatus`](../../backend/src/main/java/com/aryanyeole/wmp/payroll/repository/PayrollRunRepository.java),
[`EmployeeRepository.compareAndSetStatus`](../../backend/src/main/java/com/aryanyeole/wmp/onboarding/repository/EmployeeRepository.java)
— shaped as:

```java
UPDATE <Entity> e SET e.status = :next WHERE e.id = :id AND e.status = :expected
```

The row count it returns (0 or 1) is the whole signal: 1 means this caller's
read was still true at write time and the transition is exclusively theirs;
0 means someone else moved the row first. Each service's own
`compareAndSetStatusOrConflict` helper acts on that: a loss re-fetches the
current row and re-runs the *existing* `requireTransition` check against it,
so the caller gets the identical "Cannot transition ... from X to Y" wording
the ordinary, non-racing illegal-transition case already produces — one
message-building path, not a second one that can drift — and that surfaces
as a `409` through the existing `GlobalExceptionHandler`/`ProblemDetail`
path, no new exception type.

### The cycle's consequence for the chosen guard

Each `compareAndSetStatusOrConflict` ends with a trailing, explicit
`ConflictException`, reached only if the re-run `requireTransition` check
returns normally instead of throwing.

For `ExpenseStatus` and `PayrollRunStatus`, every transition this guards
targets a status that is either the sole outgoing edge or terminal — there
is no path back to a status once left behind. A failed compare-and-swap
there always means the current status has moved strictly past what was
expected, so the re-run `requireTransition` is guaranteed to throw. The
trailing `ConflictException` in `ExpenseService`/`PayrollService` is
unreachable today — dead code, kept as a defensive fallback against that
reasoning being wrong someday, not because it fires in practice.

For `EmploymentStatus` it is reachable, live, not defensive. `EmployeeTransitions.requireTransition`
explicitly no-ops when `current == target` — a deliberate idempotency
allowance for a sequential caller re-issuing the same PATCH. That means two
callers racing the *identical* target — both asking to move `ACTIVE` ->
`ON_LEAVE`, say — can have the loser's post-loss re-check see
`current == target` (the winner already got there) and return normally
instead of throwing. Without the explicit trailing throw, that caller would
get a silent `200` for a transition it did not itself win — the exact
failure mode this ADR exists to close, reappearing one layer down. The
trailing throw is what stops it: the loser's own read was stale at the
moment it decided to act, and a `409` is the correct report of that
regardless of what the row ended up holding.

### Deliberate duplication, not an oversight

The same six-or-so lines — the `@Modifying @Query` and its
`compareAndSetStatusOrConflict` caller — exist three times, once per entity,
rather than behind one entity-agnostic, parametrized helper. This is a
decision, recorded here so it isn't mistaken for something left unfinished:
a generic version would need to abstract over the entity type, the status
enum type, and each service's own visibility/re-fetch call
(`findVisible`/`requireVisible`/`requireRun`), and the result reads worse
than the three explicit copies for a reader trying to answer "what does
submitting an expense actually do" without first understanding a generic
helper's type parameters. This code is written to be read (CLAUDE.md); three
short, boring, identically-shaped methods cost little and stay legible on
their own. Consolidating remains a reasonable future refactor — worth doing
the next time any of the three needs to change — just not one this fix
took on.

## Evidence

Deterministic reproduction, no sleeps: real threads released together by a
`CyclicBarrier`, calling the service directly.

- [`ExpenseSubmitConcurrencyIT`](../../backend/src/test/java/com/aryanyeole/wmp/expense/service/ExpenseSubmitConcurrencyIT.java)
  — 10 threads racing `submit` on the same `DRAFT`. Against the unfixed code:
  `expected: 1 but was: 10` (all ten succeeded, zero conflicts). Against the
  fix: one success, nine `ConflictException`s, exactly one `SUBMITTED`
  `approval_events` row.
- [`PayrollSubmitConcurrencyIT`](../../backend/src/test/java/com/aryanyeole/wmp/payroll/service/PayrollSubmitConcurrencyIT.java)
  — same shape, same result: `expected: 1 but was: 10` before the fix, one
  winner and nine conflicts after.
- [`EmployeeUpdateConcurrencyIT`](../../backend/src/test/java/com/aryanyeole/wmp/onboarding/service/EmployeeUpdateConcurrencyIT.java)
  — two cases. `concurrentEmploymentStatusTransitions_exactlyOneWins` (10
  racers on the same `PENDING -> ACTIVE` update) produced the identical
  `expected: 1 but was: 10` before the fix. `losingRaceLeavesOtherPatchedFieldsUntouched`
  (two racers, same target status, each pairing it with its own distinct
  `firstName`) failed before the fix with "exactly one of the two racers
  should win: Expecting value to be true but was false" — both racers
  reported success, and nothing in the assertion could tell them apart. Both
  cases pass against the fix, including the check that the losing racer's
  `firstName` never reached the database.
- The dev database itself carried the original evidence: two `SUBMITTED`
  `approval_events` rows for expense report 132001 (ids 77 and 78, ~6ms
  apart), left in place rather than cleaned up, from the double-click that
  first surfaced this.
- Full suite: 148 tests, 0 failures after the expense/payroll fix
  (commit `7e7aebc`); 151 tests, 0 failures after the employee fix (commit
  `6d4bbdc`), all via `.\mvnw.cmd clean verify`. TODO: the exact
  freshly-verified test count immediately *before* `7e7aebc` was not
  independently re-run in that task — only the post-fix 148 was — so the
  145-tests figure from Phase 9's own framing is not repeated here as a
  verified before/after pair.

## Consequences

**What this buys:**
- All three read-check-then-write state transitions in this codebase close
  the same race, verified red-then-green against real concurrent load, not
  assumed correct from reading the diff.
- The loser of a race gets exactly the same `409` wording a caller attempting
  an ordinary illegal transition already gets — no new error shape for
  callers to learn, no silent success anywhere this pattern occurs.
- No schema migration, no new exception type, no dependency on ORM lock/flush
  semantics being used correctly by every future caller.

**What this makes harder / does not cover:**
- The pattern is duplicated three times on purpose (see above) — a fourth
  entity gaining the same shape means a fourth near-identical copy, not a
  reusable call.
- [`OnboardingTaskService.update`](../../backend/src/main/java/com/aryanyeole/wmp/onboarding/service/OnboardingTaskService.java)
  has no transition guard at all — any status is accepted from any status,
  and no `approval_events` row is written — so it is outside this ADR's
  scope entirely. There is nothing here for a race to bypass.
- A more general last-write-wins problem remains unaddressed: two concurrent
  `PATCH` requests touching *different* fields on the same row (an
  onboarding task's `title` versus its `status`, for instance) can still
  have one silently clobber the other's change, since Hibernate's default
  `UPDATE` writes every mapped column, not only the ones a given request
  changed. That is a distinct problem from the one this ADR fixes — no
  transition guard is bypassed, no state machine is violated, nothing here
  addresses it — and it is not fixed by this decision.
