# Raw evidence: payroll-submit 500s under the accrual leak (Phase 8 Task 2)

**Status:** raw material only — no diagnosis, no narrative. Task 3 reads this and writes the actual postmortem.
**Date:** 2026-08-30 (local time UTC-7; all timestamps below are UTC unless marked otherwise)
**Setup:** fresh backend JVM, `-Dspring-boot.run.profiles=dev`, default (untouched) Hikari settings (`maximum-pool-size` unset → HikariCP's built-in default of 10). `wmp-db` container, port 5433. `docker compose --profile observability` was **not** running for this capture (Grafana/Prometheus screenshot explicitly marked a nice-to-have, not required — skipped to avoid delaying the reproduction).

## Timeline

| Time (UTC) | Event |
|---|---|
| 21:15:39.659 | Load generator started: 8 concurrent workers, 240s duration, against `POST /api/v1/payroll/runs/{id}/submit`, cycling randomly over 29 fresh DRAFT runs (see "Runs used" below) |
| 21:15:40.101 | First successful (`200`) submit |
| 21:15:44.676 | Last successful (`200`) submit — all 28 successful transitions land within the first ~5 seconds; every DRAFT run gets consumed almost immediately once 8 workers race over only 29 of them, and every submit after this point against an already-submitted run correctly 409s |
| 21:16:17.776 | `POST /actuator/payroll-accrual` triggered (backgrounded, concurrently with the submit traffic — not before or after it) |
| 21:16:20.041 | First `500` recorded in submit traffic (request had been in flight ~30s — see "Note on this specific line" below) |
| 21:16:50.328 | Accrual trigger's own HTTP call finally returns: `500`, `time=32.312746s` |
| 21:19:22.080 | Last `500` / last row of submit traffic (load generator's 240s window ends) |
| 21:22:06.711 | Isolated post-completion check, ~2m45s after the load generator finished and ~5m49s after the trigger was sent: still `500`, `time=30.026321s` — **no self-recovery** |

### Note on the first-500 timestamp

The worker script logs a timestamp immediately before issuing each `curl` call, then records `%{time_total}`. Arithmetically, the first `500` row (`ts=21:16:20.041`, `elapsed=30.083s`) implies that request *started* around `21:15:49.958` — nearly 28 seconds *before* the accrual trigger was even sent. This is likely a measurement artifact of the load generator's design (8 independent bash loops each forking a fresh `date`+`curl` process per iteration; under sustained concurrent process-spawn load, an individual iteration's logged start time can lag its actual request start by a non-trivial amount). Flagging this rather than silently trusting the arithmetic — it does not affect the aggregate/log-based timeline above, which is corroborated independently by the application log's own timestamps (first HikariPool timeout logged in-app at `14:16:50.248-07:00` local = `21:16:50.248Z`, i.e. ~32.5s after the trigger, consistent with one full `connection-timeout` cycle after the pool was driven to exhaustion).

## Status codes (submit traffic, whole 240s window)

```
     28 200
    814 409
     56 500
```
(898 total requests recorded; `898 = 28+814+56`.) 500s cluster from `21:16:20` (first) through `21:19:22` (last, i.e. the window's own end — see "self-recovery" below for what happened after).

## Application log: verbatim HikariPool timeout lines

119 occurrences total in this run's log. First full occurrence with context:

```
2026-08-30T14:16:50.248-07:00  WARN 14628 --- [wmp] [nio-8080-exec-2] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: null
2026-08-30T14:16:50.248-07:00  WARN 14628 --- [wmp] [nio-8080-exec-2] org.hibernate.orm.jdbc.error             : HikariPool-1 - Connection is not available, request timed out after 30014ms (total=10, active=10, idle=0, waiting=8)
2026-08-30T14:16:50.272-07:00  WARN 14628 --- [wmp] [nio-8080-exec-1] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: null
2026-08-30T14:16:50.272-07:00  WARN 14628 --- [wmp] [nio-8080-exec-1] org.hibernate.orm.jdbc.error             : HikariPool-1 - Connection is not available, request timed out after 30008ms (total=10, active=10, idle=0, waiting=6)
2026-08-30T14:16:50.272-07:00  WARN 14628 --- [wmp] [nio-8080-exec-9] org.hibernate.orm.jdbc.error             : HHH000247: ErrorCode: 0, SQLState: null
2026-08-30T14:16:50.272-07:00  WARN 14628 --- [wmp] [nio-8080-exec-9] org.hibernate.orm.jdbc.error             : HikariPool-1 - Connection is not available, request timed out after 30009ms (total=10, active=10, idle=0, waiting=6)
2026-08-30T14:16:50.282-07:00 ERROR 14628 --- [wmp] [nio-8080-exec-9] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction] with root cause

java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30009ms (total=10, active=10, idle=0, waiting=6)
	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:714) ~[HikariCP-7.0.2.jar:na]
	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:184) ~[HikariCP-7.0.2.jar:na]
	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:142) ~[HikariCP-7.0.2.jar:na]
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:127) ~[HikariCP-7.0.2.jar:na]
	at org.hibernate.engine.jdbc.connections.internal.DataSourceConnectionProvider.getConnection(DataSourceConnectionProvider.java:149) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.internal.NonContextualJdbcConnectionAccess.obtainConnection(NonContextualJdbcConnectionAccess.java:62) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl.acquire(LogicalConnectionManagedImpl.java:187) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl.acquireConnectionIfNeeded(LogicalConnectionManagedImpl.java:87) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl.getPhysicalConnection(LogicalConnectionManagedImpl.java:126) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl.getConnectionForTransactionManagement(LogicalConnectionManagedImpl.java:281) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl.begin(LogicalConnectionManagedImpl.java:290) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorImpl$TransactionDriverControlImpl.begin(JdbcResourceLocalTransactionCoordinatorImpl.java:226) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.hibernate.engine.transaction.internal.TransactionImpl.begin(TransactionImpl.java:75) ~[hibernate-core-7.4.5.Final.jar:7.4.5.Final]
	at org.springframework.orm.jpa.vendor.HibernateJpaDialect.beginTransaction(HibernateJpaDialect.java:147) ~[spring-orm-7.0.9.jar:7.0.9]
	at org.springframework.orm.jpa.JpaTransactionManager.doBegin(JpaTransactionManager.java:411) ~[spring-orm-7.0.9.jar:7.0.9]
	at org.springframework.transaction.support.AbstractPlatformTransactionManager.startTransaction(AbstractPlatformTransactionManager.java:532) ~[spring-tx-7.0.9.jar:7.0.9]
	at org.springframework.transaction.support.AbstractPlatformTransactionManager.getTransaction(AbstractPlatformTransactionManager.java:405) ~[spring-tx-7.0.9.jar:7.0.9]
```

`total=10, active=10, idle=0` on every single one of the 119 occurrences, from the first through the last, `waiting` fluctuating 0-9 — never once did `idle` read anything but 0 for the rest of this run.

## Response bodies

**`POST /actuator/payroll-accrual` (the trigger itself, once its own connection needs get starved by its own leak):**
```json
{"timestamp":"2026-08-30T21:16:50.328Z","status":500,"error":"Internal Server Error","path":"/actuator/payroll-accrual"}
```

**`POST /api/v1/payroll/runs/{id}/submit` under exhaustion:**
```json
{"timestamp":"2026-08-30T21:23:17.032Z","status":500,"error":"Internal Server Error","path":"/api/v1/payroll/runs/51/submit"}
```

Both are Spring Boot's generic default error body, **not** this project's usual RFC 7807 `ProblemDetail` shape — noted as a raw observation, not something fixed in this task.

## hikaricp.connections.* over the course of the run (`/actuator/metrics/hikaricp.connections.*`, sampled every ~12-14s)

```
sample #1  21:16:23.586Z  active=10.0 idle=0.0 pending=9.0 timeout=0.0
sample #2  21:16:38.099Z  active=10.0 idle=0.0 pending=9.0 timeout=0.0
sample #3  21:16:52.437Z  active=10.0 idle=0.0 pending=8.0 timeout=9.0
sample #4  21:17:06.939Z  active=10.0 idle=0.0 pending=8.0 timeout=9.0
   ...
sample #17 21:20:11.141Z  active=10.0 idle=0.0 pending=0.0 timeout=57.0
sample #18 21:20:25.109Z  active=10.0 idle=0.0 pending=0.0 timeout=57.0
sample #19 21:20:39.043Z  active=10.0 idle=0.0 pending=0.0 timeout=57.0
```
(full 19-sample log retained at `docs/incidents/payroll-500s-samples/evidence_samples.txt`.) `pending` (threads currently waiting for a connection) drops to 0 only once the load generator itself stops sending new requests (`pending=0` needs no in-flight callers, not a healthy pool) — `active` stays at 10 and `idle` stays at 0 for the entire captured window, including the final samples.

## `pg_stat_activity`

**By state** (captured after the incident, pool still exhausted):
```
 state  | count 
--------+-------
 active |     1
 idle   |    10
```

**Idle-in-transaction sessions:**
```
 pid | state | age | query 
-----+-------+-----+-------
(0 rows)
```
Zero, every time this was checked (once per sample throughout the run, plus this final check) — the leaked connections never show as `idle in transaction`.

**The 10 leaked backends themselves** (queried directly against `application_name LIKE '%JDBC%'`, ~7m17s-19s after they were opened):
```
 pid  | state |       age       |                          last_query                          
------+-------+-----------------+--------------------------------------------------------------
 1558 | idle  | 00:07:18.945131 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1557 | idle  | 00:07:18.841806 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1559 | idle  | 00:07:18.688065 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1561 | idle  | 00:07:18.501248 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1562 | idle  | 00:07:18.338286 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1560 | idle  | 00:07:18.220296 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1563 | idle  | 00:07:18.045095 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1564 | idle  | 00:07:17.884366 | INSERT INTO payroll_accruals (employee_id, period_start, per
 1566 | idle  | 00:07:17.7241   | INSERT INTO payroll_accruals (employee_id, period_start, per
 1565 | idle  | 00:07:17.584887 | INSERT INTO payroll_accruals (employee_id, period_start, per
```
All 10 last ran `INSERT INTO payroll_accruals (...)`, all state `idle`, all opened within ~1.4 seconds of each other.

## Self-recovery check

Isolated request at `21:22:06.711Z` (~2m45s after the load generator's own 240s window ended, ~5m49s after the accrual trigger was sent): `status=500 time=30.026321`. Final metrics at that moment: `active=10.0 idle=0.0 pending=0.0 timeout=58.0`. The pool did not recover on its own at any point this was checked.

## Grafana / Prometheus

Not captured — the observability profile (`docker compose --profile observability`) was not started for this run, per the task's own "don't block on it if it adds friction" allowance. All the same numbers above are independently available at `/actuator/metrics/hikaricp.connections.*` and `/actuator/prometheus` any time the app is running in this same broken state, if a screenshot is wanted later.

## Runs used / setup notes (for reproducing this capture, not part of the narrative)

- 30 fresh `DRAFT` payroll runs created for previously-unused periods (`2026-09` through `2029-02`, minus one collision), each given exactly one `PayrollItem` so they're submittable. Run ids recorded in the scratchpad's `draft_run_ids.txt` (not committed — dev-DB-specific).
- One methodological correction made during this task: `CreatePayrollItemRequest`'s field is `grossCents`, not `grossPayCents` — the first attempt at seeding items used the wrong field name, which Jackson rejected outright (`400 Failed to read request`) since `grossCents` is a required primitive `long`. That first, broken attempt's evidence (0% success rate, 100% "no items" 409s) is kept at `docs/incidents/payroll-500s-samples/run1-item-seeding-bug/` for the record, but is **not** used as the basis for the status-code/timeline numbers above — those come from the corrected second run, which has a realistic 200/409/500 mix.
- Load generator: `load/generate_submit_traffic.sh` (new, committed this task) — 8 concurrent background worker loops rather than Phase 6/7's single-threaded harnesses, since reproducing pool exhaustion needs genuinely overlapping in-flight requests, not controlled sequential depth measurement.
