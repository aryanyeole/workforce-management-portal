-- V4__onboarding_documents_metadata.sql
-- Reason: Phase 4 document upload validates content type and file size,
-- and GET .../documents returns metadata only. V1__baseline.sql has no
-- columns to persist that metadata for later display/audit (only
-- document_type, file_name, storage_path). Adding both here rather than
-- editing V1.

ALTER TABLE onboarding_documents ADD COLUMN content_type TEXT;
ALTER TABLE onboarding_documents ADD COLUMN file_size_bytes BIGINT;
