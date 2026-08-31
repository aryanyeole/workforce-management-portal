# ADR 0002: Keyset pagination for the expense approvals queue

**Status:** Accepted
**Date:** 2026-08-23
**Phase:** 6 (Scale the data, measure offset pagination, replace it with keyset)

## Context

`GET /api/v1/expenses/approvals` used `LIMIT`/`OFFSET` pagination. Phase 6 measured it against a 70,000-row `expense_reports` table (20,976 `SUBMITTED`) across four states — {offset, keyset} × {no index, index} — before deciding anything. All figures below are from `docs/measurements.md`; nothing here is invented or rounded for effect.

## Decision

Replace offset pagination with keyset pagination — ordering by `(submitted_at DESC, id DESC)`, an opaque base64 cursor over the last row's own `(submitted_at, id)` — **and** keep the partial index `idx_expense_reports_pending_queue` on `(submitted_at DESC) WHERE status = 'SUBMITTED' AND deleted_at IS NULL`. Neither change is sufficient on its own; the 2×2 is why.

### The 2×2 evidence

| | No index | Index |
|---|---|---|
| **Offset**, query time (ms) | 14.1 / 21.5 / 22.7 / 21.3 (page 1/100/500/1000) | 0.2 / 2.7 / **35.1** / **34.0** |
| **Keyset**, query time (ms) | 14.9 (shallow) / 10.6 (deep) | 0.9 (shallow) / **0.2** (deep) |

- **Index alone (offset kept):** helps enormously at shallow depth (page 1: 14.1 ms → 0.2 ms) but the planner abandons the ordered index walk at depth, switching to a `Bitmap Heap Scan` + `Sort` over the full matching set — page 500/1000 land at 35.1 ms / 34.0 ms, *worse* than the no-index baseline (22.7 ms / 21.3 ms) at the same depths. `OFFSET` still forces materializing everything up to `offset+limit` one way or another; the index changes how, not whether.
- **Keyset alone (index dropped):** does nothing. Shallow and deep execution time (14.9 ms / 10.6 ms) sit in the same range regardless of depth, because both are dominated by a full `Seq Scan` across all 70,000 rows. The keyset predicate correctly shrinks the row count reaching the final sort (996 rows deep vs. 20,976 shallow) — but with no index to use that predicate as a scan boundary, the scan itself, the actual bottleneck, is unchanged.
- **Both together:** the only cell that is fast *and* flat — 0.9 ms shallow, 0.2 ms deep, no relationship between depth and cost. HTTP p50 confirms it at the endpoint level: ~21-26 ms flat across every depth, versus offset+index's ~28-32 ms (shallow) stepping up to ~42-49 ms (deep) at the same plan-flip boundary, and versus keyset-without-index's ~38-47 ms flat (still no depth effect, just uniformly slower without the index).

### The predicate-form discovery

The keyset boundary is a row comparison, `(submitted_at, id) < (cursorAt, cursorId)`. JPA's `CriteriaBuilder` has no row-value constructor, so the direct translation is the boolean expansion:

```java
cb.or(
    cb.lessThan(root.get("submittedAt"), submittedAt),
    cb.and(cb.equal(root.get("submittedAt"), submittedAt), cb.lessThan(root.get("id"), id)));
```

This is logically identical to the row comparison. It is not treated identically by the planner. `EXPLAIN` at a deep cursor with this form:

```
Filter: ((submitted_at < '...'::timestamptz) OR ((submitted_at = '...'::timestamptz) AND (id < 100861)))
Rows Removed by Filter: 19980
Execution Time: 11.034 ms
```

— the scan starts at the beginning of the index and walks past, discarding, every row before the cursor. Postgres does not recognize an `OR`-of-`AND`s as equivalent to a row-value comparison for the purpose of deriving an index range condition; only literal `ROW(a, b) < ROW(x, y)` syntax gets that treatment, and JPA's Criteria API has no way to emit it. The fix, Hibernate's `HibernateCriteriaBuilder.sql(pattern, type, args...)` — an escape hatch that splices a native SQL fragment into the compiled query — emitting a literal row comparison instead:

```java
((HibernateCriteriaBuilder) cb).sql("(?, ?) < (?::timestamptz, ?)", Boolean.class,
        root.get("submittedAt"), root.get("id"), cb.literal(submittedAt.toString()), cb.literal(id));
```

brought the same query to:

```
Index Cond: (submitted_at <= '...'::timestamptz)
Filter: (ROW(submitted_at, id) < ROW('...'::timestamptz, 100861))
Rows Removed by Filter: 1
Execution Time: 0.248 ms
```

`Rows Removed by Filter` dropped from 19,980 to 1.

### The two bugs found getting there

1. **Bare `?` placeholders.** `sql()`'s placeholders are positional and unnumbered — not `?1`/`?2`. Writing `"(?1, ?2) < (?3, ?4)"` compiled without error into `(er1_0.submitted_at1, er1_0.id2) < (...)` — the digit was emitted as literal trailing text — and failed loudly at query time with a Postgres syntax error.
2. **Timezone-dependent literal.** `cb.literal(submittedAt)` (an `Instant`) rendered as a bare `timestamp '...'`, with no zone. Casting that to `timestamptz` doesn't recover UTC — it reinterprets the already-zoneless value using the session's local timezone. This one did not fail loudly: the query ran, returned 200, and returned plausible-looking rows, but the cursor's own boundary row came back in the "before" result set. **It was found manually** — by checking that a cursor built from a known row's exact `(submittedAt, id)` excluded that row from the next page — not by any test at the time, since none of the existing tests checked that specific thing. Fixed by casting the ISO-8601 string form (`submittedAt.toString()`, ending in `Z`) directly, parsed as UTC regardless of session timezone. Regression tests were added afterward, once the gap was recognized: an explicit boundary-exclusion assertion, a tie-breaking test forcing several rows to an identical `submitted_at` across a page split, and pinning the test session to a deliberately non-UTC, non-whole-hour timezone (`Asia/Kolkata`, via `connection-init-sql` + `@ActiveProfiles`) with a test asserting the pin holds — chosen over "fail if not UTC" because that check's outcome depends on which machine runs the suite (confirmed this dev machine's ambient DB session timezone is `America/Phoenix`, not UTC), not on anything the code does.

### Why the partial index beat the 2-column composite

The existing partial index, `(submitted_at DESC) WHERE status = 'SUBMITTED' AND deleted_at IS NULL`, was built for offset pagination (Task B) and reused unchanged for keyset. A scratch `(submitted_at DESC, id DESC)` composite index was benchmarked twice:

- **Against the still-broken OR-expansion predicate:** 34% faster (13.337 ms → 8.805 ms). This looked like grounds for a migration, and very nearly became one.
- **Against the corrected row-value predicate:** 0.248 ms → 0.225 ms — a 23 microsecond difference, noise at this scale, invisible in the HTTP measurements.

The 34% figure was real but came from the composite index accidentally giving the *broken* query a shorter path, not from fixing the actual problem; it does not survive once the predicate itself is correct. The 2-column index does produce a technically cleaner plan under the fixed predicate (a true `Index Cond`, no residual `Filter`, no `Incremental Sort`), but per CLAUDE.md's requirement that a new index be justified by the actual query rather than a guess, 23 µs isn't justification. **No new migration was added.**

## Trade-offs actually accepted

- **No random page access.** A cursor only ever means "continue from here" — there is no equivalent of "jump to page 500." Reaching depth N costs N/size sequential requests to walk there (this is exactly what `load/measure_approvals_keyset.sh` has to do as untimed setup to even measure a given depth).

  **Phase 9 update (Task 2/2b):** this cost nothing on the one client that actually consumes this endpoint. The approvals UI is infinite scroll, not a page-number widget — it never wanted "jump to page 500" in the first place, so the tradeoff this ADR accepted turned out to be free in practice. What *did* cost something was unrelated to keyset: Task 2 found the unvirtualized UI's wall-clock render cost grew ~13x from the first batch to the 4,000th while `GET /api/v1/expenses/approvals` itself stayed flat the entire time (network p50 ~35-46 ms regardless of depth) — confirming the degradation was in React's fully-mounted row tree, not in this pagination design. Task 2b's virtualization fix (see `docs/measurements.md`) brought wall-clock cost to a flat ~2.2 ms/row through the full 20,976-row queue, with network latency unchanged. Keyset held up; the client-side rendering strategy was the actual bottleneck all along.
- **No total count without a second query.** The response never reports how many pending approvals exist in total; `ExpenseApprovalsKeysetRepository` deliberately fetches `limit + 1` rows to learn whether more remain, instead of a `COUNT(*)`, because a count query is exactly the per-request cost keyset pagination exists to avoid.
- **Cursor invalidation if the sort key changes.** The cursor is only meaningful relative to the exact ordering it was issued under. If the endpoint's sort ever changes (e.g., ordering by amount instead of submission time), every previously-issued cursor becomes meaningless.
- **Harder arbitrary sorting.** Offset pagination composes with "sort by any column the client asks for" for free. Keyset pagination needs a matching index and a matching row-comparison predicate for each sort order it supports — this queue only ever needed one order (`submitted_at DESC, id DESC`), so the question of a second sortable field never came up here, but it would be real work if it did.
- **The escape hatch's own cost.** `HibernateCriteriaBuilder.sql(...)` is Hibernate-specific, not portable to a different JPA provider, and the SQL fragment is a plain string — Hibernate does not type-check `"(?, ?) < (?::timestamptz, ?)"` against the entity's actual column types the way the rest of the Criteria API does. A typo in that string fails at query execution, not at compile time (as the bare-`?` placeholder bug did) — the codebase now carries one hand-verified raw-SQL fragment instead of zero.

## What offset pagination is still better at

- **Jumping to an arbitrary page number.** A UI "go to page 40" control has no keyset equivalent; keyset only supports "next" (and, if a previous-cursor were added, "back").
- **Showing a total result count or a page-count widget** ("page 3 of 1,049") without paying for a separate `COUNT(*)`.
- **Sorting by whatever column the client requests.** Offset pagination's `ORDER BY` can be parameterized per-request against any indexed (or even unindexed, just slower) column with no additional predicate work; keyset needs a purpose-built comparison and matching index per supported sort order.
- **Shallow-depth simplicity when the total result set is already small** — none of this queue's problems (the plan flip in state 2, the predicate-form bug in state 3) show up until either the offset or the table size gets large enough to matter; for a small, bounded list, offset pagination's simpler mental model (a page number) is not paying for anything it doesn't need.

## Consequences

**What this buys:**
- Query time and HTTP latency at the deepest measured depth (page 1000 / 19,980 rows in) are indistinguishable from page 1 — 0.2 ms vs. 0.9 ms query time, ~21-26 ms vs. ~21-26 ms HTTP p50 — a property neither offset pagination nor the index alone provided at any point in this investigation.
- The fix path is now documented in code (`ExpenseSpecifications.beforeCursor`'s comment) and in this ADR, so the next person who reaches for the "obvious" boolean-expansion predicate for a similar keyset query elsewhere in the codebase has both the reasoning and the regression-test pattern to reuse.

**What this makes harder:**
- No random page access, no cheap total count, sort-key changes invalidate outstanding cursors, and a second supported sort order would mean a second hand-written row-comparison predicate and (likely) a second index — all listed above, none hypothetical.
- The `HibernateCriteriaBuilder.sql(...)` fragment is a real, if small, portability and type-safety cost: it is Hibernate-specific and untyped from the compiler's point of view, unlike every other predicate in `ExpenseSpecifications`.
- The bug that caused the most damage here (silent, timezone-dependent boundary leakage returning valid-looking 200s) was not caught by any automated test until after it was found manually and fixed — the regression tests that would have caught it were written afterward, not before.
