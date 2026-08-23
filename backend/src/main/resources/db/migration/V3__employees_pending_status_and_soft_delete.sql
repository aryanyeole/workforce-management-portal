-- V3__employees_pending_status_and_soft_delete.sql
-- Reason: Phase 4 needs a PENDING employment_status (new hires start
-- PENDING, then transition to ACTIVE once onboarded) and a soft-delete
-- marker on employees, matching DELETE /api/v1/onboarding/employees/{id}.
-- V1__baseline.sql only allowed ACTIVE/ON_LEAVE/TERMINATED with a default
-- of ACTIVE and has no deletion marker; since it's never edited once
-- applied, both changes land here instead.
--
-- The CHECK constraint name is Postgres's own default naming for an
-- unnamed inline CHECK on a single column (`{table}_{column}_check`),
-- which is what V1's inline CHECK on employment_status produced.

ALTER TABLE employees DROP CONSTRAINT employees_employment_status_check;
ALTER TABLE employees ADD CONSTRAINT employees_employment_status_check
    CHECK (employment_status IN ('PENDING', 'ACTIVE', 'ON_LEAVE', 'TERMINATED'));
ALTER TABLE employees ALTER COLUMN employment_status SET DEFAULT 'PENDING';

ALTER TABLE employees ADD COLUMN deleted_at TIMESTAMPTZ;
