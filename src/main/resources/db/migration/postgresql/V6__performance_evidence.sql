ALTER TABLE performance_entry
    DROP CONSTRAINT IF EXISTS ck_performance_evidence_type;
ALTER TABLE performance_entry
    ADD CONSTRAINT ck_performance_evidence_type
        CHECK (evidence_type IN ('CERTIFICATE', 'CONTRACT', 'TAX_INVOICE'));

CREATE TABLE performance_evidence (
    id VARCHAR(36) PRIMARY KEY,
    entry_id VARCHAR(36) NOT NULL REFERENCES performance_entry (id) ON DELETE CASCADE,
    evidence_type VARCHAR(30) NOT NULL,
    selected_file_id BIGINT,
    selected_drive_file_id VARCHAR(36) REFERENCES performance_drive_file (id),
    selected_uploaded_file_id VARCHAR(36) REFERENCES performance_uploaded_file (id),
    filename VARCHAR(2000) NOT NULL,
    file_ext VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_performance_evidence_row_type
        CHECK (evidence_type IN ('CERTIFICATE', 'CONTRACT', 'TAX_INVOICE')),
    CONSTRAINT ck_performance_evidence_source CHECK (
        (CASE WHEN selected_file_id IS NULL THEN 0 ELSE 1 END
       + CASE WHEN selected_drive_file_id IS NULL THEN 0 ELSE 1 END
       + CASE WHEN selected_uploaded_file_id IS NULL THEN 0 ELSE 1 END) = 1
    )
);

CREATE INDEX ix_performance_evidence_entry
    ON performance_evidence (entry_id, created_at, id);

INSERT INTO performance_evidence(
    id, entry_id, evidence_type, selected_file_id, selected_drive_file_id,
    selected_uploaded_file_id, filename, file_ext, created_at, updated_at)
SELECT gen_random_uuid()::text, e.id, e.evidence_type, e.selected_file_id, e.selected_drive_file_id,
       e.selected_uploaded_file_id, e.selected_filename, e.selected_ext, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM performance_entry e
WHERE e.evidence_type IS NOT NULL OR e.selected_file_id IS NOT NULL
   OR e.selected_drive_file_id IS NOT NULL OR e.selected_uploaded_file_id IS NOT NULL
   OR e.selected_filename IS NOT NULL OR e.selected_ext IS NOT NULL;

INSERT INTO performance_evidence(
    id, entry_id, evidence_type, selected_file_id, selected_drive_file_id,
    selected_uploaded_file_id, filename, file_ext, created_at, updated_at)
SELECT gen_random_uuid()::text, a.entry_id, a.evidence_type, a.selected_file_id, a.selected_drive_file_id,
       a.selected_uploaded_file_id, a.filename, a.ext, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM performance_additional_evidence a;

DO $$
DECLARE
    expected_count BIGINT;
    migrated_count BIGINT;
BEGIN
    SELECT
        (SELECT COUNT(*) FROM performance_entry e
         WHERE e.evidence_type IS NOT NULL OR e.selected_file_id IS NOT NULL
            OR e.selected_drive_file_id IS NOT NULL OR e.selected_uploaded_file_id IS NOT NULL
            OR e.selected_filename IS NOT NULL OR e.selected_ext IS NOT NULL)
        + (SELECT COUNT(*) FROM performance_additional_evidence)
    INTO expected_count;

    SELECT COUNT(*) INTO migrated_count FROM performance_evidence;

    IF migrated_count <> expected_count THEN
        RAISE EXCEPTION 'performance evidence backfill count mismatch: expected %, migrated %',
            expected_count, migrated_count;
    END IF;
END $$;
