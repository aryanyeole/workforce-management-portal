-- V1__baseline.sql
-- Baseline schema: departments, roles, employees, user_accounts, onboarding,
-- payroll, expenses, approval_events, audit_log.
--
-- Conventions used throughout:
--   * BIGSERIAL primary keys.
--   * Enums are `text` + a CHECK constraint, never native Postgres enum types
--     or JPA ordinals, so new values are a migration, not a code deploy.
--   * created_at / updated_at on every table that models mutable state.
--     updated_at is maintained by trigger so it stays correct even for rows
--     touched by raw SQL (batch jobs, psql), not just JPA writes.
--   * approval_events and audit_log are append-only event logs: created_at
--     only, no updated_at.

-- ---------------------------------------------------------------------------
-- updated_at trigger
-- ---------------------------------------------------------------------------

CREATE FUNCTION trigger_set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- roles
-- ---------------------------------------------------------------------------

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE
                    CHECK (code IN ('EMPLOYEE', 'MANAGER', 'PAYROLL_ADMIN', 'HR_ADMIN', 'SYSTEM')),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

INSERT INTO roles (code, description) VALUES
    ('EMPLOYEE', 'Self-service access to own payslips, expenses, and onboarding tasks'),
    ('MANAGER', 'Approves direct reports'' expense reports and onboarding tasks'),
    ('PAYROLL_ADMIN', 'Runs and approves payroll'),
    ('HR_ADMIN', 'Manages employees, departments, and onboarding'),
    ('SYSTEM', 'Service-to-service and scheduled-job principal');

-- ---------------------------------------------------------------------------
-- departments
-- ---------------------------------------------------------------------------

CREATE TABLE departments (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON departments
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- employees
-- ---------------------------------------------------------------------------

CREATE TABLE employees (
    id                BIGSERIAL PRIMARY KEY,
    department_id     BIGINT REFERENCES departments (id),
    manager_id        BIGINT REFERENCES employees (id),
    first_name        TEXT NOT NULL,
    last_name         TEXT NOT NULL,
    email             TEXT NOT NULL UNIQUE,
    hire_date         DATE NOT NULL,
    employment_status TEXT NOT NULL DEFAULT 'ACTIVE'
                          CHECK (employment_status IN ('ACTIVE', 'ON_LEAVE', 'TERMINATED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_employees_department_id ON employees (department_id);
CREATE INDEX idx_employees_manager_id ON employees (manager_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON employees
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- user_accounts
-- ---------------------------------------------------------------------------

CREATE TABLE user_accounts (
    id            BIGSERIAL PRIMARY KEY,
    employee_id   BIGINT UNIQUE REFERENCES employees (id),
    role_id       BIGINT NOT NULL REFERENCES roles (id),
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_accounts_role_id ON user_accounts (role_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON user_accounts
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- onboarding_tasks
-- ---------------------------------------------------------------------------

CREATE TABLE onboarding_tasks (
    id           BIGSERIAL PRIMARY KEY,
    employee_id  BIGINT NOT NULL REFERENCES employees (id),
    title        TEXT NOT NULL,
    description  TEXT,
    status       TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')),
    due_date     DATE,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_onboarding_tasks_employee_id ON onboarding_tasks (employee_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON onboarding_tasks
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- onboarding_documents
-- ---------------------------------------------------------------------------

CREATE TABLE onboarding_documents (
    id            BIGSERIAL PRIMARY KEY,
    employee_id   BIGINT NOT NULL REFERENCES employees (id),
    document_type TEXT NOT NULL,
    file_name     TEXT NOT NULL,
    storage_path  TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'PENDING_REVIEW'
                      CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_onboarding_documents_employee_id ON onboarding_documents (employee_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON onboarding_documents
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- expense_categories
-- ---------------------------------------------------------------------------

CREATE TABLE expense_categories (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- expense_reports
-- ---------------------------------------------------------------------------

CREATE TABLE expense_reports (
    id          BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    category_id BIGINT NOT NULL REFERENCES expense_categories (id),
    amount      NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency    TEXT NOT NULL DEFAULT 'USD',
    description TEXT,
    status      TEXT NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    submitted_at TIMESTAMPTZ,
    approver_id BIGINT REFERENCES user_accounts (id),
    approved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_expense_reports_employee_id ON expense_reports (employee_id);
CREATE INDEX idx_expense_reports_category_id ON expense_reports (category_id);
CREATE INDEX idx_expense_reports_approver_id ON expense_reports (approver_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON expense_reports
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- payroll_runs
-- ---------------------------------------------------------------------------

CREATE TABLE payroll_runs (
    id           BIGSERIAL PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL CHECK (period_end >= period_start),
    status       TEXT NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    submitted_by BIGINT REFERENCES user_accounts (id),
    submitted_at TIMESTAMPTZ,
    approved_by  BIGINT REFERENCES user_accounts (id),
    approved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_runs_submitted_by ON payroll_runs (submitted_by);
CREATE INDEX idx_payroll_runs_approved_by ON payroll_runs (approved_by);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- payroll_items
-- ---------------------------------------------------------------------------

CREATE TABLE payroll_items (
    id             BIGSERIAL PRIMARY KEY,
    payroll_run_id BIGINT NOT NULL REFERENCES payroll_runs (id),
    employee_id    BIGINT NOT NULL REFERENCES employees (id),
    gross_pay      NUMERIC(12, 2) NOT NULL CHECK (gross_pay >= 0),
    deductions     NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (deductions >= 0),
    net_pay        NUMERIC(12, 2) NOT NULL CHECK (net_pay >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (payroll_run_id, employee_id)
);

CREATE INDEX idx_payroll_items_employee_id ON payroll_items (employee_id);

CREATE TRIGGER set_updated_at BEFORE UPDATE ON payroll_items
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ---------------------------------------------------------------------------
-- approval_events (append-only)
-- ---------------------------------------------------------------------------

CREATE TABLE approval_events (
    id          BIGSERIAL PRIMARY KEY,
    entity_type TEXT NOT NULL
                    CHECK (entity_type IN ('EXPENSE_REPORT', 'PAYROLL_RUN', 'ONBOARDING_TASK')),
    entity_id   BIGINT NOT NULL,
    actor_id    BIGINT NOT NULL REFERENCES user_accounts (id),
    action      TEXT NOT NULL
                    CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_approval_events_entity ON approval_events (entity_type, entity_id);
CREATE INDEX idx_approval_events_actor_id ON approval_events (actor_id);

-- ---------------------------------------------------------------------------
-- audit_log (append-only)
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    BIGINT REFERENCES user_accounts (id),
    action      TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   BIGINT,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_actor_id ON audit_log (actor_id);
