-- Phase 8: backing table for the nightly payroll accrual batch job
-- (com.aryanyeole.wmp.payroll.job.PayrollAccrualJob). One row per
-- employee per period, upserted as the job re-runs across a period.
--
-- created_at/updated_at follow the same convention as every other table
-- (see V1's trigger_set_updated_at()) deliberately: the job's write path
-- goes through raw JDBC (see the job class for why), bypassing
-- BaseEntity's @PrePersist/@PreUpdate, so updated_at correctness depends
-- entirely on this trigger — exactly the case BaseEntity's own javadoc
-- already calls out ("stays correct for rows touched by raw SQL too").

CREATE TABLE payroll_accruals (
    id             BIGSERIAL PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES employees (id),
    period_start   DATE NOT NULL,
    period_end     DATE NOT NULL,
    accrued_amount NUMERIC(12, 2) NOT NULL,
    computed_at    TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, period_start, period_end)
);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON payroll_accruals
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE INDEX idx_payroll_accruals_employee ON payroll_accruals (employee_id);
