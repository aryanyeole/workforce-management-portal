-- load/seed.sql
--
-- Phase 6 scale seed: ~2,000 employees and 70,000 expense_reports (with a
-- realistic status/date spread), plus a modest amount of payroll data.
-- Generates the offset-vs-keyset pagination measurements' dataset.
--
-- WHY A RAW SQL SCRIPT, NOT A @Profile("seed") SPRING RUNNER:
--   - Bulk generation via generate_series() + array-indexed random picks
--     inserts tens of thousands of rows in one INSERT...SELECT, in seconds.
--     A JPA/Hibernate runner persisting 70,000+ individual entities would
--     be dominated by ORM/session overhead and take vastly longer for data
--     that is thrown away between measurement runs.
--   - This data must never run automatically in any environment (dev
--     bootstrap, CI, Testcontainers ITs) — unlike DevDataSeeder, which is
--     deliberately wired into the app's own startup lifecycle. A plain SQL
--     script that only runs when someone explicitly points psql at it is
--     the safer default; there is no accidental-activation path.
--   - CLAUDE.md's own repo layout already designates /load for exactly
--     this ("seed data + k6 load scripts").
--
-- Prerequisites: the app must have started at least once with the "dev"
-- profile so DevDataSeeder has created departments/expense_categories/
-- roles/user_accounts — this script reuses those rather than duplicating
-- them.
--
-- Run against the local dev Postgres (docker-compose's db service, NOT
-- the Testcontainers-backed CI/IT database, which this script never
-- touches):
--   docker exec -i wmp-db psql -U wmp -d wmp -f - < load/seed.sql
-- or, with psql installed locally:
--   psql -h localhost -p 5433 -U wmp -d wmp -f load/seed.sql
--
-- Repeatable: re-running against an already-seeded database raises a
-- clear error instead of silently duplicating 60k+ rows; truncate the
-- load-test rows first (see bottom of this file) if you want to reseed.

\timing on

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM employees WHERE email LIKE 'loadtest.%@wmp-load.dev' LIMIT 1) THEN
        RAISE EXCEPTION 'Load-test employees already exist — this script is not meant to run twice on the same database. See the truncate snippet at the bottom of load/seed.sql if you want to reseed from scratch.';
    END IF;
    IF (SELECT count(*) FROM departments) = 0 THEN
        RAISE EXCEPTION 'No departments found. Start the app once with the "dev" profile so DevDataSeeder runs, then re-run this script.';
    END IF;
    IF (SELECT count(*) FROM expense_categories) = 0 THEN
        RAISE EXCEPTION 'No expense_categories found. Start the app once with the "dev" profile so DevDataSeeder runs, then re-run this script.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM user_accounts ua JOIN roles r ON r.id = ua.role_id WHERE r.code = 'PAYROLL_ADMIN') THEN
        RAISE EXCEPTION 'No PAYROLL_ADMIN user_account found. Start the app once with the "dev" profile so DevDataSeeder runs, then re-run this script.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. Employees: ~2,000 across the existing departments, with a manager
--    hierarchy (1 manager per 10, ~200 managers total) — "several hundred
--    approvers" for the MANAGER-scoped view of the pending queue, even
--    though the single deep measurement uses the org-wide PAYROLL_ADMIN
--    account (see docs/measurements.md).
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE load_employee_ids (rn BIGINT PRIMARY KEY, id BIGINT NOT NULL);

WITH dept_ids AS (
    SELECT array_agg(id) AS ids FROM departments
),
inserted AS (
    INSERT INTO employees (department_id, manager_id, first_name, last_name, email, hire_date, employment_status, created_at, updated_at)
    SELECT
        d.ids[1 + floor(random() * array_length(d.ids, 1))::int],
        NULL,
        'LoadTest',
        'Employee' || gs,
        'loadtest.employee' || gs || '@wmp-load.dev',
        (DATE '2018-01-01' + (random() * 2800)::int),
        'ACTIVE',
        now(), now()
    FROM generate_series(1, 2000) AS gs, dept_ids d
    RETURNING id
)
INSERT INTO load_employee_ids (rn, id)
SELECT row_number() OVER (ORDER BY id), id FROM inserted;

-- Every 10th employee (by insertion order) manages the other 9 in its group.
UPDATE employees e
SET manager_id = mgr.id
FROM load_employee_ids e_row
JOIN load_employee_ids mgr ON mgr.rn = ((e_row.rn - 1) / 10) * 10 + 1
WHERE e.id = e_row.id
  AND e_row.rn % 10 <> 1;

-- ---------------------------------------------------------------------------
-- 2. Expense reports: 70,000. Status mix: 30% SUBMITTED (~21,000 — just
--    over the requested 15-20k pending-approval range, deliberately:
--    the org-wide PAYROLL_ADMIN approver used for the deep-page
--    measurement needs to comfortably clear offset 19,980 (page 1000 at
--    size 20) with real rows still left afterward, not an empty tail),
--    20% DRAFT, 35% APPROVED, 15% REJECTED. submitted_at spread over the
--    last ~24 months; approver_id/approved_at set only for
--    APPROVED/REJECTED.
-- ---------------------------------------------------------------------------
WITH category_ids AS (
    SELECT array_agg(id) AS ids FROM expense_categories
),
approver_ids AS (
    SELECT array_agg(ua.id) AS ids
    FROM user_accounts ua JOIN roles r ON r.id = ua.role_id
    WHERE r.code IN ('MANAGER', 'PAYROLL_ADMIN')
),
employee_ids AS (
    SELECT array_agg(id) AS ids FROM load_employee_ids
),
generated AS (
    SELECT
        gs,
        random() AS roll,
        now() - (random() * 730) * interval '1 day' AS submitted_at_candidate
    FROM generate_series(1, 70000) AS gs
)
INSERT INTO expense_reports (
    employee_id, category_id, amount, currency, description,
    status, submitted_at, approver_id, approved_at, created_at, updated_at
)
SELECT
    e.ids[1 + floor(random() * array_length(e.ids, 1))::int],
    c.ids[1 + floor(random() * array_length(c.ids, 1))::int],
    round((10 + random() * 2990)::numeric, 2),
    'USD',
    'Load test expense #' || g.gs,
    CASE
        WHEN g.roll < 0.30 THEN 'SUBMITTED'
        WHEN g.roll < 0.50 THEN 'DRAFT'
        WHEN g.roll < 0.85 THEN 'APPROVED'
        ELSE 'REJECTED'
    END,
    CASE WHEN g.roll >= 0.30 AND g.roll < 0.50 THEN NULL ELSE g.submitted_at_candidate END,
    CASE WHEN g.roll >= 0.50 THEN a.ids[1 + floor(random() * array_length(a.ids, 1))::int] ELSE NULL END,
    CASE WHEN g.roll >= 0.50
         THEN LEAST(g.submitted_at_candidate + (random() * 14) * interval '1 day', now())
         ELSE NULL END,
    now(), now()
FROM generated g, employee_ids e, category_ids c, approver_ids a;

-- ---------------------------------------------------------------------------
-- 3. Payroll: 24 monthly runs (expenses are the focus, this is just
--    "non-trivial") — most recent DRAFT, the two before SUBMITTED, the
--    rest APPROVED. ~300 items per run, one per (run, employee) as the
--    UNIQUE constraint requires.
-- ---------------------------------------------------------------------------
WITH months AS (
    SELECT gs AS n, (date_trunc('month', now()) - (gs || ' months')::interval)::date AS period_start
    FROM generate_series(0, 23) AS gs
),
payroll_admin AS (
    SELECT ua.id FROM user_accounts ua JOIN roles r ON r.id = ua.role_id WHERE r.code = 'PAYROLL_ADMIN' LIMIT 1
),
inserted_runs AS (
    INSERT INTO payroll_runs (period_start, period_end, status, submitted_by, submitted_at, approved_by, approved_at, created_at, updated_at)
    SELECT
        m.period_start,
        (m.period_start + interval '1 month' - interval '1 day')::date,
        CASE WHEN m.n = 0 THEN 'DRAFT' WHEN m.n <= 2 THEN 'SUBMITTED' ELSE 'APPROVED' END,
        CASE WHEN m.n = 0 THEN NULL ELSE pa.id END,
        CASE WHEN m.n = 0 THEN NULL ELSE (m.period_start + interval '1 month')::timestamptz END,
        CASE WHEN m.n <= 2 THEN NULL ELSE pa.id END,
        CASE WHEN m.n <= 2 THEN NULL ELSE (m.period_start + interval '1 month' + interval '3 days')::timestamptz END,
        now(), now()
    FROM months m, payroll_admin pa
    RETURNING id
)
SELECT * INTO TEMP load_payroll_run_ids FROM inserted_runs;

WITH run_employee_pairs AS (
    SELECT r.id AS run_id, e.id AS employee_id,
           row_number() OVER (PARTITION BY r.id ORDER BY random()) AS rn
    FROM load_payroll_run_ids r CROSS JOIN load_employee_ids e
),
selected AS (
    SELECT run_id, employee_id,
           round((3000 + random() * 5000)::numeric, 2) AS gross
    FROM run_employee_pairs WHERE rn <= 300
)
INSERT INTO payroll_items (payroll_run_id, employee_id, gross_pay, tax, deductions, net_pay, created_at, updated_at)
SELECT
    run_id, employee_id, gross,
    round(gross * 0.15, 2),
    round(gross * 0.05, 2),
    gross - round(gross * 0.15, 2) - round(gross * 0.05, 2),
    now(), now()
FROM selected;

-- ---------------------------------------------------------------------------
-- 4. Refresh planner statistics — stale stats would invalidate the EXPLAIN
--    plans this data exists to produce.
-- ---------------------------------------------------------------------------
ANALYZE employees;
ANALYZE expense_reports;
ANALYZE payroll_runs;
ANALYZE payroll_items;

-- ---------------------------------------------------------------------------
-- Report actual counts.
-- ---------------------------------------------------------------------------
SELECT 'employees (load-test)' AS what, count(*) FROM employees WHERE email LIKE 'loadtest.%@wmp-load.dev'
UNION ALL
SELECT 'employees (total)', count(*) FROM employees
UNION ALL
SELECT 'expense_reports (total)', count(*) FROM expense_reports
UNION ALL
SELECT 'expense_reports (SUBMITTED)', count(*) FROM expense_reports WHERE status = 'SUBMITTED'
UNION ALL
SELECT 'expense_reports (DRAFT)', count(*) FROM expense_reports WHERE status = 'DRAFT'
UNION ALL
SELECT 'expense_reports (APPROVED)', count(*) FROM expense_reports WHERE status = 'APPROVED'
UNION ALL
SELECT 'expense_reports (REJECTED)', count(*) FROM expense_reports WHERE status = 'REJECTED'
UNION ALL
SELECT 'payroll_runs (total)', count(*) FROM payroll_runs
UNION ALL
SELECT 'payroll_items (total)', count(*) FROM payroll_items;

-- ---------------------------------------------------------------------------
-- To reseed from scratch, first remove everything this script created
-- (order matters: children before parents):
--
--   DELETE FROM payroll_items WHERE employee_id IN (SELECT id FROM employees WHERE email LIKE 'loadtest.%@wmp-load.dev');
--   DELETE FROM payroll_runs WHERE submitted_by IS NOT NULL AND id NOT IN (SELECT payroll_run_id FROM payroll_items);
--   DELETE FROM expense_reports WHERE employee_id IN (SELECT id FROM employees WHERE email LIKE 'loadtest.%@wmp-load.dev');
--   UPDATE employees SET manager_id = NULL WHERE email LIKE 'loadtest.%@wmp-load.dev';
--   DELETE FROM employees WHERE email LIKE 'loadtest.%@wmp-load.dev';
-- ---------------------------------------------------------------------------
