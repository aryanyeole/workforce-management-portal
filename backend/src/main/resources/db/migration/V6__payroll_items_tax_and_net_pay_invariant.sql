-- V6__payroll_items_tax_and_net_pay_invariant.sql
-- Reason: V1__baseline.sql has no column for tax withholding, but Phase 5
-- requires net_pay = gross_pay - tax - deductions, which needs a tax
-- amount distinct from other deductions. Adding the column and a CHECK
-- constraint that mirrors the same invariant the application computes
-- (PayrollService derives net_pay server-side, so this constraint should
-- never actually fire — it is the "not only in the DB check" backstop,
-- not the primary enforcement).

ALTER TABLE payroll_items ADD COLUMN tax NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (tax >= 0);

ALTER TABLE payroll_items ADD CONSTRAINT payroll_items_net_pay_invariant
    CHECK (net_pay = gross_pay - tax - deductions);
