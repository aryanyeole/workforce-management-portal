-- V2__expense_reports_soft_delete.sql
-- Reason: Phase 3's DELETE /api/v1/expenses/{id} is a soft delete, but
-- V1__baseline.sql (already applied) has no deletion marker on
-- expense_reports. V1 is never edited once applied, so this adds the
-- column here instead.
--
-- deleted_at is nullable with no default: existing rows (including any
-- dev-seeded data) come through as "not deleted" automatically. No index is
-- added — see ROADMAP Phase 7 for when composite/partial indexes on this
-- table are actually measured and added, not guessed at up front.

ALTER TABLE expense_reports ADD COLUMN deleted_at TIMESTAMPTZ;
