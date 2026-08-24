# Measurements

## Phase 6, Task 2 — Offset pagination baseline (`GET /api/v1/expenses/approvals`)

**Date:** 2026-08-23
**Environment:** Postgres 16.15 (`docker-compose` `db` service, local dev container `wmp-db`, already running — not started for this task). `expense_reports`: 70,000 rows, 18 MB table / 23 MB total relation size. App run via `.\mvnw.cmd spring-boot:run` (dev profile) with SQL logging enabled **only** via command-line arguments (`--logging.level.org.hibernate.SQL=DEBUG --logging.level.org.hibernate.orm.jdbc.bind=TRACE`) — no file was edited, and the process was stopped again once the measurement was done. Data is from `load/seed.sql` (Task 1).

No application code or configuration file was changed to produce these numbers.

---

### Step 0 — Verifying the dataset actually supports the measurement

**Requested query, run as-is:**

```sql
SELECT approver_id, COUNT(*) FROM expense_reports WHERE status='SUBMITTED' GROUP BY approver_id ORDER BY 2 DESC LIMIT 10;
```

```
 approver_id | count
-------------+-------
             | 20976
(1 row)
```

Distinct non-null `approver_id` values among SUBMITTED rows: **0**. This is expected, not a bug in the seed: `approver_id` is only ever populated at decision time (`ExpenseService.decide()`, on APPROVED/REJECTED) — a pending SUBMITTED row never has one. The pending queue's per-approver visibility is decided by `VisibilityScope` through `employees.manager_id` (for MANAGER) or is `Unrestricted` (for PAYROLL_ADMIN), never through `expense_reports.approver_id`.

**So the real question is the per-manager distribution, via the path VisibilityScope actually uses:**

```sql
SELECT e.manager_id, COUNT(*) AS pending_count
FROM expense_reports er JOIN employees e ON e.id = er.employee_id
WHERE er.status = 'SUBMITTED' AND e.manager_id IS NOT NULL
GROUP BY e.manager_id ORDER BY pending_count DESC LIMIT 10;
```

```
 manager_id | pending_count
------------+---------------
       2391 |           116
       3631 |           115
       2731 |           115
       3261 |           113
       2241 |           112
       2061 |           112
       2971 |           112
       2151 |           111
       2651 |           110
       2281 |           110
(10 rows)
```

205 distinct managers hold a pending queue; **max 116 rows, average 94.2 rows**. No MANAGER's queue is anywhere near page 100 (offset 1,980), let alone page 1000.

**Principal chosen for this measurement:** `katherine.johnson@wmp.dev` (role `PAYROLL_ADMIN`). `VisibilityScopeResolver` resolves `PAYROLL_ADMIN` to `Unrestricted` — this is the **widest-scope case the authorization model grants** (per the task's preference order, option 1), **not a typical manager's queue**. Its query returns all SUBMITTED rows: `totalElements: 20976` in the live HTTP response, matching the direct `COUNT(*)`.

**Page 1000 check:** offset 19,980 + limit 20 = 20,000 ≤ 20,976 total → **996 rows remain after the offset, so page 1000 returns a full 20-row page**, not an empty one. Confirmed directly in the EXPLAIN output below (`Seq Scan ... rows=20976`, same for every depth) and in the HTTP response bodies (all four requests returned exactly 20 items in `content`).

---

### Step 1 — The real SQL, captured via temporary logging

From the `org.hibernate.SQL` / `org.hibernate.orm.jdbc.bind` log (not hand-written):

```sql
select er1_0.id,er1_0.amount,er1_0.approved_at,er1_0.approver_id,er1_0.category_id,er1_0.created_at,er1_0.currency,er1_0.deleted_at,er1_0.description,er1_0.employee_id,er1_0.status,er1_0.submitted_at,er1_0.updated_at
from expense_reports er1_0
where er1_0.deleted_at is null and er1_0.status=? and 1=1
order by er1_0.submitted_at desc
offset ? rows fetch first ? rows only
```

(`1=1` is Hibernate's rendering of `VisibilityScope.Unrestricted`'s `cb.conjunction()` predicate — see `VisibilityScopeSpecifications`.) A companion query also runs for `totalElements`:

```sql
select count(er1_0.id) from expense_reports er1_0 where er1_0.deleted_at is null and er1_0.status=? and 1=1
```

Bound parameters, confirmed via TRACE log for each request actually made against the running app:

| Page | `page` query param | offset | limit |
|---|---|---|---|
| 1 | 0 | 0 | 20 |
| 100 | 99 | 1,980 | 20 |
| 500 | 499 | 9,980 | 20 |
| 1000 | 999 | 19,980 | 20 |

`EXPLAIN (ANALYZE, BUFFERS)` run directly against the same database, using this exact SQL text with the captured literal values substituted in:

**Page 1 (offset 0):**
```
 Limit  (cost=3687.31..3687.36 rows=20 width=114) (actual time=14.053..14.061 rows=20 loops=1)
   Buffers: shared hit=2256
   ->  Sort  (cost=3687.31..3739.86 rows=21019 width=114) (actual time=14.051..14.057 rows=20 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: top-N heapsort  Memory: 29kB
         Buffers: shared hit=2256
         ->  Seq Scan on expense_reports er1_0  (cost=0.00..3128.00 rows=21019 width=114) (actual time=0.593..8.934 rows=20976 loops=1)
               Filter: ((deleted_at IS NULL) AND (status = 'SUBMITTED'::text))
               Rows Removed by Filter: 49024
               Buffers: shared hit=2253
 Planning:
   Buffers: shared hit=152
 Planning Time: 0.988 ms
 Execution Time: 14.142 ms
```

**Page 100 (offset 1,980):**
```
 Limit  (cost=4390.49..4390.54 rows=20 width=114) (actual time=21.262..21.283 rows=20 loops=1)
   Buffers: shared hit=2256
   ->  Sort  (cost=4385.54..4438.09 rows=21019 width=114) (actual time=20.712..21.133 rows=2000 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: top-N heapsort  Memory: 545kB
         Buffers: shared hit=2256
         ->  Seq Scan on expense_reports er1_0  (cost=0.00..3128.00 rows=21019 width=114) (actual time=0.540..11.542 rows=20976 loops=1)
               Filter: ((deleted_at IS NULL) AND (status = 'SUBMITTED'::text))
               Rows Removed by Filter: 49024
               Buffers: shared hit=2253
 Planning:
   Buffers: shared hit=152
 Planning Time: 0.854 ms
 Execution Time: 21.500 ms
```

**Page 500 (offset 9,980):**
```
 Limit  (cost=4654.52..4654.57 rows=20 width=114) (actual time=22.468..22.479 rows=20 loops=1)
   Buffers: shared hit=2256
   ->  Sort  (cost=4629.57..4682.11 rows=21019 width=114) (actual time=20.988..22.155 rows=10000 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: top-N heapsort  Memory: 2867kB
         Buffers: shared hit=2256
         ->  Seq Scan on expense_reports er1_0  (cost=0.00..3128.00 rows=21019 width=114) (actual time=0.675..10.010 rows=20976 loops=1)
               Filter: ((deleted_at IS NULL) AND (status = 'SUBMITTED'::text))
               Rows Removed by Filter: 49024
               Buffers: shared hit=2253
 Planning:
   Buffers: shared hit=152
 Planning Time: 0.956 ms
 Execution Time: 22.676 ms
```

**Page 1000 (offset 19,980):**
```
 Limit  (cost=4687.05..4687.10 rows=20 width=114) (actual time=21.068..21.081 rows=20 loops=1)
   Buffers: shared hit=2256
   ->  Sort  (cost=4637.10..4689.65 rows=21019 width=114) (actual time=17.563..20.430 rows=20000 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: quicksort  Memory: 3401kB
         Buffers: shared hit=2256
         ->  Seq Scan on expense_reports er1_0  (cost=0.00..3128.00 rows=21019 width=114) (actual time=0.587..9.252 rows=20976 loops=1)
               Filter: ((deleted_at IS NULL) AND (status = 'SUBMITTED'::text))
               Rows Removed by Filter: 49024
               Buffers: shared hit=2253
 Planning:
   Buffers: shared hit=152
 Planning Time: 0.819 ms
 Execution Time: 21.313 ms
```

**Summary:**

| Page | Offset | Execution Time | Rows scanned (Seq Scan) | Rows discarded by filter | Sort method | Sort memory |
|---|---|---|---|---|---|---|
| 1 | 0 | 14.142 ms | 20,976 | 49,024 | top-N heapsort | 29 kB |
| 100 | 1,980 | 21.500 ms | 20,976 | 49,024 | top-N heapsort | 545 kB |
| 500 | 9,980 | 22.676 ms | 20,976 | 49,024 | top-N heapsort | 2,867 kB |
| 1000 | 19,980 | 21.313 ms | 20,976 | 49,024 | quicksort (full) | 3,401 kB |

---

### HTTP end-to-end latency (p50) — **SUPERSEDED, see Task A below**

> **This subsection's numbers are confounded and should not be cited.**
> The measurement ran all 30 samples of page 1, then all 30 of page 100,
> then 500, then 1000, sequentially, with no warmup. p50 *decreased*
> monotonically with page depth (84.8ms → 79.6ms → 76.6ms → 70.7ms),
> which has no plausible mechanism given the EXPLAIN plans above all cost
> about the same — the actual cause was almost certainly JIT/connection
> -pool warmup improving over the course of the run, correlated with
> depth only because depth was measured in a fixed, blocked order. Task A
> (below) fixes the method — discarded warmup, randomized order across
> depths, p50 **and** p95 — and re-measures this same state (offset
> pagination, no index) as the real baseline. The EXPLAIN (ANALYZE,
> BUFFERS) plans and the Step 0 dataset-verification findings above are
> unaffected by this — only the HTTP timing numbers immediately below are
> superseded.

**Method:** 30 sequential `GET` requests per page depth against the running app (`http://localhost:8080`, dev profile, same `PAYROLL_ADMIN` bearer token throughout), one request at a time (no concurrency), timed with `curl -w "%{time_total}"`. p50 is the median of each page's 30 samples.

| Page | n | min (s) | p50 (s) | max (s) | mean (s) |
|---|---|---|---|---|---|
| 1 | 30 | 0.0601 | 0.0848 | 0.1639 | 0.0885 |
| 100 | 30 | 0.0596 | 0.0796 | 0.1920 | 0.0839 |
| 500 | 30 | 0.0590 | 0.0766 | 0.1078 | 0.0769 |
| 1000 | 30 | 0.0460 | 0.0707 | 0.1147 | 0.0697 |

---

## Phase 6, Task A — Corrected HTTP measurement method, state 1 re-baselined

**Date:** 2026-08-23
**What changed and why:** the harness above conflated depth with measurement order. `load/measure_approvals_offset.sh` (new, committed) fixes this:

- **Discarded warmup** — 20 requests per depth (80 total) run first and are thrown away, so JIT compilation, connection-pool establishment, and buffer-cache warming happen before any timed sample.
- **Randomized order across depths** — the 400 timed requests (100 per depth × 4 depths) are shuffled into one randomized sequence (`shuf`) and issued in that order, not blocked by depth. Any warmup/thermal drift remaining after the discarded phase is now uncorrelated with depth instead of confounded with it.
- **p50 and p95 reported, with n stated** — nearest-rank percentile over the sorted samples.

Same app instance, same dev database (post-`load/seed.sql`, unchanged since Task 1/2), same `PAYROLL_ADMIN` principal (`katherine.johnson@wmp.dev`), same four depths (page 1/100/500/1000, size 20), same "no index" schema state — this is a like-for-like re-measurement, not a new scenario. Run twice to confirm reproducibility:

**Run 1** (`./load/measure_approvals_offset.sh http://localhost:8080 "$TOKEN" 100 20`):
```
page1     n=100  min=0.0407 p50=0.0637 p95=0.0800 max=0.0844 mean=0.0656
page100   n=100  min=0.0421 p50=0.0670 p95=0.0868 max=0.0960 mean=0.0683
page500   n=100  min=0.0460 p50=0.0719 p95=0.0902 max=0.1020 mean=0.0746
page1000  n=100  min=0.0432 p50=0.0716 p95=0.0922 max=0.0993 mean=0.0729
```

**Run 2** (same command, immediately after):
```
page1     n=100  min=0.0454 p50=0.0605 p95=0.0860 max=0.2167 mean=0.0663
page100   n=100  min=0.0395 p50=0.0671 p95=0.0873 max=0.4579 mean=0.0754
page500   n=100  min=0.0430 p50=0.0704 p95=0.0940 max=0.6498 mean=0.0797
page1000  n=100  min=0.0407 p50=0.0707 p95=0.0894 max=0.3378 mean=0.0756
```

**Reading:** with the confound removed, p50 now shows a small, monotonic **increase** with depth (page 1 ≈ 60-64ms → page 1000 ≈ 71ms, both runs), consistent with the EXPLAIN findings — deeper pages sort marginally more rows before the top-N cutoff is decided (sort memory 29kB → 545kB → 2,867kB → 3,401kB across depths in the earlier plans) — but the effect is small, not the dramatic "deep pages are dramatically slower" story, because every depth still pays for the same full 70k-row `Seq Scan` and near-full sort regardless of offset. This matches, and firms up, the EXPLAIN-based conclusion already reached: **without an index, offset depth barely matters, because the dominant cost (full scan + full sort) is already paid at every depth.** State 2 (Task B: add the index) is where a real depth-dependent curve should appear, since an index removes the full-sort floor that's currently flattening these numbers.

State 1 (offset, no index) is now considered the confirmed baseline for the 2×2 comparison in Task D.

---

### Honest reading of these numbers

This is the important, slightly counter-intuitive result, and I'm reporting it as measured rather than forcing it into the "offset gets progressively slower" story that's often told about this pattern: **without any index on `status` or `submitted_at`, execution time does not scale with offset depth here.** Every plan runs a full `Seq Scan` over all 70,000 rows (discarding the same 49,024 non-matching rows every time) and sorts essentially the entire ~21,000-row matching set before `LIMIT`/`OFFSET` is ever applied — because there's no index to provide pre-sorted order, Postgres can't avoid materializing (most of) the sorted result regardless of which page is requested. The classic "small pages fast, deep pages slow" offset story assumes an index lets shallow pages skip the full sort; here, every page already pays close to the full cost, so page 1000 (21.3 ms, p50 70.7 ms) is not dramatically worse than page 1 (14.1 ms, p50 84.8 ms) — if anything both execution time and HTTP p50 are flat to mildly *decreasing* across this range in this run.

That does not mean offset pagination is fine here — it means the current bottleneck is "no index at all," which is Phase 7's job, deliberately not done in this phase. What keyset pagination (Task 3/4) changes even without an index: its `WHERE (submitted_at, id) < (?, ?)` shrinks the candidate row set as paging progresses — rows already "seen" stop matching the predicate — whereas `OFFSET` always sorts the same full ~21k-row matching set no matter how deep the page. That's a real, distinct, measurable difference Task 4 will capture. It is a different claim from "removes the index-free full scan," which no pagination style can do by itself — composite indexing stays out of scope for this phase.

**Constraints followed:** no application code or config file changed (logging enabled via CLI args only); no index added; `V1__baseline.sql` untouched; `application.yml` untouched.

---

## Phase 6, Task B — State 2: composite (partial) index + offset

**Date:** 2026-08-23
**Migration:** `V7__expense_reports_pending_queue_index.sql` — `CREATE INDEX idx_expense_reports_pending_queue ON expense_reports (submitted_at DESC) WHERE status = 'SUBMITTED' AND deleted_at IS NULL;`. Applied via Flyway (app startup against the dev `wmp-db`), then `ANALYZE expense_reports;` run directly. Full derivation and the composite-vs-partial reasoning are in the migration file's header comment — summary: the query's equality-filtered columns (`status`, `deleted_at`) and its sort column (`submitted_at DESC`) are the composite-index derivation, but since this endpoint only ever queries `status = 'SUBMITTED'`, that equality dimension folds into a partial predicate instead of stored key data — smaller index, and DRAFT-forever rows never enter it. No application code changed.

### Confirming the index is actually used, and what happens to the Sort node

`EXPLAIN (ANALYZE, BUFFERS)` on the same real SQL as Task A/Task 2 (`status='SUBMITTED'`, `1=1` for PAYROLL_ADMIN's `Unrestricted` scope), same four depths:

**Page 1 (offset 0):**
```
 Limit  (cost=0.29..9.47 rows=20 width=114) (actual time=0.057..0.147 rows=20 loops=1)
   Buffers: shared hit=20 read=2
   ->  Index Scan using idx_expense_reports_pending_queue on expense_reports er1_0  (cost=0.29..9564.08 rows=20823 width=114) (actual time=0.055..0.142 rows=20 loops=1)
         Buffers: shared hit=20 read=2
 Planning:
   Buffers: shared hit=186 read=1
 Planning Time: 1.859 ms
 Execution Time: 0.216 ms
```

**Page 100 (offset 1,980):**
```
 Limit  (cost=909.68..918.87 rows=20 width=114) (actual time=2.598..2.631 rows=20 loops=1)
   Buffers: shared hit=2001 read=5
   ->  Index Scan using idx_expense_reports_pending_queue on expense_reports er1_0  (cost=0.29..9564.08 rows=20823 width=114) (actual time=0.034..2.504 rows=2000 loops=1)
         Buffers: shared hit=2001 read=5
 Planning:
   Buffers: shared hit=169
 Planning Time: 1.671 ms
 Execution Time: 2.709 ms
```

**Page 500 (offset 9,980):**
```
 Limit  (cost=4375.41..4375.46 rows=20 width=114) (actual time=34.643..34.661 rows=20 loops=1)
   Buffers: shared hit=1206 read=52
   ->  Sort  (cost=4350.46..4402.52 rows=20823 width=114) (actual time=31.958..34.022 rows=10000 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: top-N heapsort  Memory: 2867kB
         Buffers: shared hit=1206 read=52
         ->  Bitmap Heap Scan on expense_reports er1_0  (cost=349.61..2862.90 rows=20823 width=114) (actual time=2.550..11.488 rows=20976 loops=1)
               Recheck Cond: ((status = 'SUBMITTED'::text) AND (deleted_at IS NULL))
               Heap Blocks: exact=1196
               Buffers: shared hit=1203 read=52
               ->  Bitmap Index Scan on idx_expense_reports_pending_queue  (cost=0.00..344.40 rows=20823 width=0) (actual time=2.263..2.272 rows=20976 loops=1)
                     Buffers: shared hit=7 read=52
 Planning:
   Buffers: shared hit=169
 Planning Time: 1.916 ms
 Execution Time: 35.127 ms
```

**Page 1000 (offset 19,980):**
```
 Limit  (cost=4406.47..4406.52 rows=20 width=114) (actual time=33.508..33.538 rows=20 loops=1)
   Buffers: shared hit=1258
   ->  Sort  (cost=4356.52..4408.58 rows=20823 width=114) (actual time=28.008..32.283 rows=20000 loops=1)
         Sort Key: submitted_at DESC
         Sort Method: quicksort  Memory: 3401kB
         Buffers: shared hit=1258
         ->  Bitmap Heap Scan on expense_reports er1_0  (cost=349.61..2862.90 rows=20823 width=114) (actual time=1.969..10.930 rows=20976 loops=1)
               Recheck Cond: ((status = 'SUBMITTED'::text) AND (deleted_at IS NULL))
               Heap Blocks: exact=1196
               Buffers: shared hit=1255
               ->  Bitmap Index Scan on idx_expense_reports_pending_queue  (cost=0.00..344.40 rows=20823 width=0) (actual time=1.668..1.688 rows=20976 loops=1)
                     Buffers: shared hit=59
 Planning:
   Buffers: shared hit=169
 Planning Time: 2.191 ms
 Execution Time: 33.972 ms
```

**The finding is a plan flip, not a smooth curve.** At shallow offsets (page 1, page 100), the planner uses an ordered `Index Scan` on the new index directly — the `Sort` node is gone, exactly as expected, because the index already provides `submitted_at DESC` order. The scan only touches as many rows as it needs to walk past: **20 rows at page 1, 2,000 rows at page 100.** At deeper offsets (page 500, page 1000), the planner switches strategy entirely: a `Bitmap Index Scan` + `Bitmap Heap Scan` fetch **all 20,976** matching rows, followed by an explicit `Sort` and then `Limit`. This is a genuine cost-based decision — walking an ordered index one-by-one past offset+limit rows (10,000 / 20,000 of them) becomes more expensive than bitmap-scanning the whole matching set and sorting it, once offset+limit approaches the size of the matching set itself. The Sort node reappears at exactly the depths where this flip happens; it never disappeared for the wrong reason (index/query order mismatch) — it only reappears once the planner deliberately abandons the index-ordered path.

| Page | Offset | Plan | Rows touched | Execution Time | Buffers (top node) |
|---|---|---|---|---|---|
| 1 | 0 | Index Scan (ordered) | 20 | 0.216 ms | hit=20 read=2 |
| 100 | 1,980 | Index Scan (ordered) | 2,000 | 2.709 ms | hit=2001 read=5 |
| 500 | 9,980 | Bitmap Heap Scan + Sort | 20,976 | 35.127 ms | hit=1206 read=52 |
| 1000 | 19,980 | Bitmap Heap Scan + Sort | 20,976 | 33.972 ms | hit=1258 read=0 |

Compare to state 1 (no index, Task A): every depth cost 14-23 ms via a full `Seq Scan` + near-full sort, regardless of offset. State 2 is **faster at shallow depths** (0.2 ms and 2.7 ms vs. ~14-22 ms) but **not faster, and by execution time slightly slower, at deep depths** (35.1 ms / 34.0 ms vs. 21.3-22.7 ms) — the index helps enormously when it can be walked directly, and stops helping (reverting to roughly full-scan-and-sort behavior, plus the bitmap-scan overhead) once the offset is deep enough that the planner abandons the ordered path.

### HTTP end-to-end latency (same harness as Task A: discarded warmup, randomized order, p50/p95, n=100/depth, two runs)

**Run 1:**
```
page1     n=100  min=0.0208 p50=0.0313 p95=0.0541 max=0.0639 mean=0.0336
page100   n=100  min=0.0200 p50=0.0323 p95=0.0494 max=0.0638 mean=0.0338
page500   n=100  min=0.0362 p50=0.0491 p95=0.0759 max=0.0789 mean=0.0517
page1000  n=100  min=0.0335 p50=0.0469 p95=0.0760 max=0.0895 mean=0.0511
```

**Run 2:**
```
page1     n=100  min=0.0191 p50=0.0280 p95=0.0374 max=0.0604 mean=0.0281
page100   n=100  min=0.0179 p50=0.0302 p95=0.0469 max=0.0625 mean=0.0305
page500   n=100  min=0.0338 p50=0.0470 p95=0.0672 max=0.0734 mean=0.0476
page1000  n=100  min=0.0320 p50=0.0424 p95=0.0604 max=0.0693 mean=0.0436
```

Both runs show the same pattern the EXPLAIN plans predict: page 1/100 (~28-32 ms p50) cluster together, page 500/1000 (~42-49 ms p50) cluster together, with the jump landing between page 100 and page 500 — matching where the plan flips from ordered `Index Scan` to `Bitmap Heap Scan` + `Sort`. Degradation with depth **did appear**, as expected, once the index existed — but as a step at the plan-flip boundary, not a smooth monotonic curve.

State 2 (offset + partial index) is the confirmed second cell of the Task D 2×2 comparison.

## Phase 6, Task C — State 3: composite index + keyset pagination

**Date:** 2026-08-23
**Migration:** none. State 2's `V7__expense_reports_pending_queue_index.sql` (`(submitted_at DESC) WHERE status = 'SUBMITTED' AND deleted_at IS NULL`) turned out to be sufficient once the query predicate itself was fixed — see below. No V8 was added.
**Code:** `GET /api/v1/expenses/approvals` rewritten to keyset pagination — `ApprovalsCursor` (opaque base64 cursor over `(submittedAt, id)`), `CursorPageResponse`, `ExpenseApprovalsKeysetRepository` (hand-built `CriteriaQuery` via `EntityManager`, fetch-one-extra-row instead of a count query), `ExpenseSpecifications.beforeCursor`. `page`/`size` replaced by `cursor`/`size`; `size` bounded to 100.

### The predicate bug this task actually turned on

The obvious way to express the keyset boundary in JPA Criteria — since the API has no row-value constructor — is the boolean expansion of `(submitted_at, id) < (cursorAt, cursorId)`:

```java
cb.or(
    cb.lessThan(root.get("submittedAt"), submittedAt),
    cb.and(cb.equal(root.get("submittedAt"), submittedAt), cb.lessThan(root.get("id"), id)));
```

This is logically equivalent to the row comparison, and it's what a first pass at this task produces. `EXPLAIN` at the deep cursor (equivalent to offset 19,980 — same boundary row used throughout this task, `submitted_at = 2024-09-28 05:39:20.648735+00, id = 100861`, established the same way as Task B, via a one-time `OFFSET 19979 LIMIT 1` probe used only to locate the row, never as part of the measured design) shows why it's wrong in practice:

```
 Limit  (cost=12.20..262.54 rows=21 width=114) (actual time=10.949..10.959 rows=21 loops=1)
   Buffers: shared hit=20043
   ->  Incremental Sort  (cost=12.20..9763.63 rows=818 width=114) (actual time=10.948..10.955 rows=21 loops=1)
         Sort Key: submitted_at DESC, id DESC
         Presorted Key: submitted_at
         Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
         Buffers: shared hit=20043
         ->  Index Scan using idx_expense_reports_pending_queue on expense_reports er1_0  (cost=0.29..9726.85 rows=818 width=114) (actual time=10.883..10.899 rows=22 loops=1)
               Filter: ((submitted_at < '2024-09-28 05:39:20.648735+00'::timestamp with time zone) OR ((submitted_at = '2024-09-28 05:39:20.648735+00'::timestamp with time zone) AND (id < 100861)))
               Rows Removed by Filter: 19980
               Buffers: shared hit=20037
 Planning:
   Buffers: shared hit=181
 Planning Time: 1.225 ms
 Execution Time: 11.034 ms
```

It never flips to a `Bitmap Heap Scan` the way offset pagination did (Task B) — but `Rows Removed by Filter: 19980` shows the scan starts at the beginning of the index and walks past (and discards) every row before the cursor, one at a time, exactly what keyset pagination exists to avoid. Postgres's planner does not recognize an `OR`-of-`AND`s as equivalent to a row-value comparison for the purpose of deriving an index range condition — only literal `ROW(a, b) < ROW(x, y)` syntax gets that treatment, and JPA's `CriteriaBuilder` has no way to emit that.

**The fix:** Hibernate's `HibernateCriteriaBuilder.sql(pattern, type, args...)` — a typed escape hatch that splices a native SQL fragment into the compiled query, substituting the given Criteria expressions positionally — used to emit a literal `(a, b) < (x, y)` row comparison:

```java
return (root, query, cb) -> ((HibernateCriteriaBuilder) cb).isTrue(
        ((HibernateCriteriaBuilder) cb).sql(
                "(?, ?) < (?::timestamptz, ?)", Boolean.class,
                root.get("submittedAt"), root.get("id"),
                cb.literal(submittedAt.toString()), cb.literal(id)));
```

Two follow-on bugs surfaced while getting to that line, both worth recording since they'd silently corrupt results rather than fail loudly:

1. **Placeholder syntax.** `sql()`'s placeholders are bare `?`, positional — not `?1`/`?2`. Using `"(?1, ?2) < (?3, ?4)"` didn't error at build time; it compiled to `(er1_0.submitted_at1, er1_0.id2) < (...)` — the digit was emitted as literal trailing text after each substituted expression — and failed at query time with a Postgres syntax error (`ERROR: syntax error at or near "3"`), not silently.
2. **Timezone-dependent literal.** `cb.literal(submittedAt)` (an `Instant`) rendered as a bare `timestamp '...'` — no zone. Casting *that* to `timestamptz` doesn't recover UTC: it reinterprets the already-zoneless value using the session's local timezone. This one **did** fail silently: the query ran, returned 200, and returned plausible-looking rows — but the cursor's own boundary row came back in the "before" result set, meaning the deep-cursor test in `ExpenseApprovalsKeysetIT` would have started overlapping pages. It was only caught by manually checking that a cursor built from a known row's exact `(submittedAt, id)` excluded that row from the next page. Fixed by passing the ISO-8601 string (`submittedAt.toString()`, which ends in `Z`) and casting that string directly to `timestamptz` — parsed as UTC regardless of session timezone.

### Confirming the fixed form compiles to what the index can serve

Same deep cursor, corrected predicate, against the unmodified V7 index:

```
 Limit  (cost=3.73..76.62 rows=21 width=114) (actual time=0.160..0.165 rows=21 loops=1)
   Buffers: shared hit=34
   ->  Incremental Sort  (cost=3.73..2839.41 rows=817 width=114) (actual time=0.159..0.163 rows=21 loops=1)
         Sort Key: submitted_at DESC, id DESC
         Presorted Key: submitted_at
         Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
         Buffers: shared hit=34
         ->  Index Scan using idx_expense_reports_pending_queue on expense_reports er1_0  (cost=0.29..2802.68 rows=817 width=114) (actual time=0.029..0.096 rows=22 loops=1)
               Index Cond: (submitted_at <= '2024-09-28 05:39:20.648735+00'::timestamp with time zone)
               Filter: (ROW(submitted_at, id) < ROW('2024-09-28 05:39:20.648735+00'::timestamp with time zone, 100861))
               Rows Removed by Filter: 1
               Buffers: shared hit=25
 Planning:
   Buffers: shared hit=178
 Planning Time: 1.012 ms
 Execution Time: 0.248 ms
```

Shallow cursor (first page, no boundary condition at all):

```
 Limit  (cost=0.77..11.21 rows=21 width=114) (actual time=0.805..0.816 rows=21 loops=1)
   Buffers: shared hit=33
   ->  Incremental Sort  (cost=0.77..10487.39 rows=21093 width=114) (actual time=0.803..0.811 rows=21 loops=1)
         Sort Key: submitted_at DESC, id DESC
         Presorted Key: submitted_at
         Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
         Buffers: shared hit=33
         ->  Index Scan using idx_expense_reports_pending_queue on expense_reports er1_0  (cost=0.29..9568.65 rows=21093 width=114) (actual time=0.162..0.642 rows=22 loops=1)
               Buffers: shared hit=24
 Planning:
   Buffers: shared hit=170
 Planning Time: 1.655 ms
 Execution Time: 0.907 ms
```

Both are a plain `Index Scan` — **no flip to `Bitmap Heap Scan` at depth**, the non-negotiable criterion for this task — and `Rows Removed by Filter` collapsed from 19,980 to 1. Execution time is sub-millisecond at both depths and does not grow with depth (0.248 ms deep vs. 0.907 ms shallow — the shallow number is *larger*, plain run-to-run noise at this scale, not a depth trend).

### The index question, resolved (and one earlier finding of mine superseded)

Both plans still carry a small `Incremental Sort` node: V7 only indexes `submitted_at`, so rows tied on it (never happens here — timestamps are microsecond-precision — but the planner can't assume that) aren't guaranteed already in `id DESC` order. Earlier in this task, before finding the predicate bug, I benchmarked a scratch `(submitted_at DESC, id DESC)` index **against the still-broken OR-expansion predicate** and measured a 34% improvement (13.337 ms → 8.805 ms) — and was ready to write a V8 migration on that basis. That comparison is superseded: it was measuring the index change while the predicate was still forcing a Filter-based scan-and-discard; the 34% was real but came from the composite index accidentally giving the *broken* query a shorter path, not from fixing the actual problem.

Re-run with the **corrected** predicate, a scratch 2-column index changes nothing worth a migration:

```
 Limit  (cost=0.29..72.24 rows=21 width=114) (actual time=0.051..0.140 rows=21 loops=1)
   Buffers: shared hit=21 read=2
   ->  Index Scan using idx_scratch_test_with_id on expense_reports er1_0  (cost=0.29..2806.59 rows=819 width=114) (actual time=0.050..0.137 rows=21 loops=1)
         Index Cond: (ROW(submitted_at, id) < ROW('2024-09-28 05:39:20.648735+00'::timestamp with time zone, 100861))
         Buffers: shared hit=21 read=2
 Planning:
   Buffers: shared hit=194 read=1
 Planning Time: 1.561 ms
 Execution Time: 0.225 ms
```

The 2-column index does get a true `Index Cond` (the row comparison serves as the scan's range bound directly, no `Filter`, no `Incremental Sort` at all) — technically the cleaner plan. But the difference is 0.248 ms → 0.225 ms: 23 microseconds, noise at this scale, invisible in the HTTP measurements below. Per CLAUDE.md ("do not add composite/covering indexes without deliberate justification derived from the actual query, not a guess"), a 23 µs difference isn't justification. **Decision: no V8 migration.** The existing V7 index, with the corrected predicate, already satisfies the task's actual requirement (index scan, no bitmap flip, no depth-dependent cost); the scratch index was dropped and `ANALYZE`'d back to baseline after each test.

### Correctness verification

- `mvn verify`, full suite: **140/140 tests pass**, including the new `ExpenseApprovalsKeysetIT` (4/4: full traversal exactly-once with no dups/gaps against a known fixture total, last page's `nextCursor` is `null`, malformed cursor → 400 `ProblemDetail` (not a 500), cursor stays coherent when `size` changes between requests) and the full 104-case `RouteAuthorizationIT` matrix (VisibilityScope/authorization behavior unchanged).
- Full traversal against the real dev dataset (not just the IT's small fixture): starting from `cursor=null`, walking `nextCursor` to exhaustion at `size=100` — **210 pages, 20,976 rows collected, 20,976 unique** — exactly matching the live `SELECT count(*) FROM expense_reports WHERE status='SUBMITTED' AND deleted_at IS NULL` count, zero duplicates, zero gaps.

### Establishing offset-equivalent depths for a cursor API

There is no "page 1000" to request with keyset pagination — a cursor only ever means "continue from here." The same four depths from Tasks A/B (offset 0 / 1,980 / 9,980 / 19,980, i.e. what page 1/100/500/1000 at size 20 would have covered) are reproduced by walking `nextCursor` forward from the start and consuming exactly that many rows, once, before any timed request — the walk itself is untimed setup, not part of what's measured, exactly mirroring how the offset harness reuses the same `page=N` parameter for every timed sample at that depth rather than re-deriving it per sample. `load/measure_approvals_keyset.sh` does this walk in chunks of 100 rows/request (the endpoint's max page size) purely to keep setup fast — a cursor is an opaque marker for an exact row, so the page size used to reach it doesn't affect where it points — landing on the exact same four boundary rows Task B used, then measuring the single next request at `size=20` (matching Task A/B) repeatedly, with the same discarded-warmup + randomized-order + p50/p95/n methodology.

### HTTP end-to-end latency (same harness family as Tasks A/B: discarded warmup, randomized order, p50/p95, n=100/depth, two runs)

**Run 1:**
```
page1     n=100  min=0.0162 p50=0.0210 p95=0.0261 max=0.0363 mean=0.0214
page100   n=100  min=0.0156 p50=0.0211 p95=0.0269 max=0.0308 mean=0.0219
page500   n=100  min=0.0163 p50=0.0211 p95=0.0268 max=0.0689 mean=0.0225
page1000  n=100  min=0.0148 p50=0.0212 p95=0.0264 max=0.0294 mean=0.0218
```

**Run 2:**
```
page1     n=100  min=0.0143 p50=0.0242 p95=0.0509 max=0.0653 mean=0.0263
page100   n=100  min=0.0154 p50=0.0262 p95=0.0596 max=0.1226 mean=0.0305
page500   n=100  min=0.0147 p50=0.0247 p95=0.0540 max=0.0667 mean=0.0270
page1000  n=100  min=0.0168 p50=0.0255 p95=0.0655 max=0.1032 mean=0.0310
```

p50 is flat across all four depths in both runs (~21 ms run 1, ~24-26 ms run 2 — the run-to-run difference is overall system noise, not a depth effect within either run). This is the signature keyset pagination is supposed to produce and offset pagination (Task B) did not: **no relationship between depth and cost.** Directly comparable to Task B's same-harness numbers — page1/page1000 p50 there were 0.0313/0.0469 ms (run 1) and 0.0280/0.0424 ms (run 2), a real depth-driven gap that lands almost exactly at keyset's flat ~0.021-0.026 ms band across every depth including the deepest.

| | Task B (offset + index) p50 | Task C (keyset + index) p50 |
|---|---|---|
| page1 | 0.0313 / 0.0280 ms | 0.0210 / 0.0242 ms |
| page100 | 0.0323 / 0.0302 ms | 0.0211 / 0.0262 ms |
| page500 | 0.0491 / 0.0470 ms | 0.0211 / 0.0247 ms |
| page1000 | 0.0469 / 0.0424 ms | 0.0212 / 0.0255 ms |

State 3 (keyset + existing partial index) is the confirmed third cell of the Task D 2×2 comparison.
