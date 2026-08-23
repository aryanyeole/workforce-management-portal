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
