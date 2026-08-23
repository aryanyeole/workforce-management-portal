-- V5__payroll_runs_paid_status_and_unique_period.sql
-- Reason: Phase 5's run lifecycle is DRAFT -> SUBMITTED -> APPROVED|REJECTED
-- -> PAID, but V1__baseline.sql's CHECK constraint only allows
-- DRAFT/SUBMITTED/APPROVED/REJECTED. Also, "one run per (period_start,
-- period_end)" was never enforced as a DB constraint — only the
-- application was expected to. Both go here since V1 is never edited
-- once applied.

ALTER TABLE payroll_runs DROP CONSTRAINT payroll_runs_status_check;
ALTER TABLE payroll_runs ADD CONSTRAINT payroll_runs_status_check
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID'));

ALTER TABLE payroll_runs ADD CONSTRAINT payroll_runs_period_unique UNIQUE (period_start, period_end);
