package com.comhu.bidmonitor.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:performance-evidence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "company-db.enabled=false",
        "external-notice.scheduler.enabled=false",
        "bid.collection.scheduler.enabled=false"
})
class PerformanceEvidenceRepositoryTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PerformanceEvidenceRepository repository;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM performance_evidence");
        jdbc.update("DELETE FROM performance_additional_evidence");
        jdbc.update("DELETE FROM performance_uploaded_file");
        jdbc.update("DELETE FROM performance_entry");
        jdbc.update("DELETE FROM performance_project");
        jdbc.update("DELETE FROM performance_evidence_backfill_map");
        jdbc.update("DELETE FROM performance_drive_file");
    }

    @Test
    void storesEveryEvidenceTypeAndAllowsRepeatedTypesForOneEntry() {
        String entryId = entry();

        repository.add(entryId, companyFile(11, EvidenceType.CONTRACT), "contract-original.pdf", "pdf");
        repository.add(entryId, companyFile(12, EvidenceType.CONTRACT), "contract-change.pdf", "pdf");
        repository.add(entryId, companyFile(13, EvidenceType.TAX_INVOICE), "tax.pdf", "pdf");
        repository.add(entryId, companyFile(14, EvidenceType.CERTIFICATE), "certificate.pdf", "pdf");

        List<Evidence> evidences = repository.findByEntryId(entryId);
        assertThat(evidences).extracting(Evidence::evidenceType)
                .containsExactlyInAnyOrder(EvidenceType.CONTRACT, EvidenceType.CONTRACT,
                        EvidenceType.TAX_INVOICE, EvidenceType.CERTIFICATE);
        assertThat(evidences).allSatisfy(evidence -> {
            assertThat(evidence.createdAt()).isNotNull();
            assertThat(evidence.updatedAt()).isNotNull();
        });
    }

    @Test
    void findsUpdatesAndDeletesOneEvidenceWithoutChangingItsIdentity() {
        String entryId = entry();
        Evidence added = repository.add(entryId, companyFile(21, EvidenceType.CONTRACT), "old.pdf", "pdf");

        assertThat(repository.find(entryId, added.id())).isEqualTo(added);
        Evidence updated = repository.update(entryId, added.id(),
                companyFile(22, EvidenceType.TAX_INVOICE), "new.pdf", "pdf");

        assertThat(updated.id()).isEqualTo(added.id());
        assertThat(updated.evidenceType()).isEqualTo(EvidenceType.TAX_INVOICE);
        assertThat(updated.selectedFileId()).isEqualTo(22L);
        assertThat(updated.filename()).isEqualTo("new.pdf");

        repository.delete(entryId, added.id());
        assertThat(repository.findByEntryId(entryId)).isEmpty();
        assertThatThrownBy(() -> repository.find(entryId, added.id()))
                .isInstanceOf(PerformanceNotFoundException.class);
    }

    @Test
    void databaseRejectsMissingMultipleAndUnknownSourcesOrTypes() {
        String entryId = entry();
        driveFile("drive-one");

        assertThatThrownBy(() -> repository.add(entryId,
                new EvidenceInput(null, null, null, EvidenceType.CONTRACT), "none.pdf", "pdf"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.add(entryId,
                new EvidenceInput(31L, "drive-one", null, EvidenceType.CONTRACT), "two.pdf", "pdf"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO performance_evidence(
                    id, entry_id, evidence_type, selected_file_id, filename, created_at, updated_at)
                VALUES (?, ?, 'UNKNOWN_TYPE', ?, 'unknown.pdf', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), entryId, 32L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingEntryCascadesToAllEvidenceRows() {
        String entryId = entry();
        repository.add(entryId, companyFile(41, EvidenceType.CONTRACT), "contract.pdf", "pdf");
        repository.add(entryId, companyFile(42, EvidenceType.TAX_INVOICE), "tax.pdf", "pdf");

        jdbc.update("DELETE FROM performance_entry WHERE id = ?", entryId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM performance_evidence WHERE entry_id = ?", Integer.class, entryId)).isZero();
    }

    @Test
    void backfillsBothLegacySlotsOnceAndDoesNotRestoreDeletedEvidenceOnRestart() {
        String entryId = entry();
        jdbc.update("""
                UPDATE performance_entry
                SET evidence_type = 'CONTRACT', selected_file_id = 51,
                    selected_filename = 'legacy-contract.pdf', selected_ext = 'pdf'
                WHERE id = ?
                """, entryId);
        jdbc.update("""
                INSERT INTO performance_additional_evidence(
                    entry_id, selected_file_id, evidence_type, filename, ext)
                VALUES (?, 52, 'TAX_INVOICE', 'legacy-tax.xlsx', 'xlsx')
                """, entryId);

        rerunSchema();

        List<Evidence> migrated = repository.findByEntryId(entryId);
        assertThat(migrated).hasSize(2);
        assertThat(migrated).anySatisfy(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(EvidenceType.CONTRACT);
            assertThat(evidence.selectedFileId()).isEqualTo(51L);
            assertThat(evidence.filename()).isEqualTo("legacy-contract.pdf");
            assertThat(evidence.fileExt()).isEqualTo("pdf");
        }).anySatisfy(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(EvidenceType.TAX_INVOICE);
            assertThat(evidence.selectedFileId()).isEqualTo(52L);
            assertThat(evidence.filename()).isEqualTo("legacy-tax.xlsx");
            assertThat(evidence.fileExt()).isEqualTo("xlsx");
        });

        rerunSchema();
        assertThat(repository.findByEntryId(entryId)).hasSize(2);

        repository.delete(entryId, migrated.getFirst().id());
        rerunSchema();
        assertThat(repository.findByEntryId(entryId)).hasSize(1);

        String laterEntryId = entry();
        jdbc.update("""
                UPDATE performance_entry
                SET evidence_type = 'CERTIFICATE', selected_file_id = 53,
                    selected_filename = 'later-certificate.pdf', selected_ext = 'pdf'
                WHERE id = ?
                """, laterEntryId);
        rerunSchema();

        assertThat(repository.findByEntryId(entryId)).hasSize(1);
        assertThat(repository.findByEntryId(laterEntryId)).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(EvidenceType.CERTIFICATE);
            assertThat(evidence.selectedFileId()).isEqualTo(53L);
            assertThat(evidence.filename()).isEqualTo("later-certificate.pdf");
        });
    }

    @Test
    void upgradesIntermediateBackfillMapWithoutBackfilledAtAndPreservesItsMapping() {
        String entryId = entry();
        String evidenceId = UUID.randomUUID().toString();
        String preservedEntryId = entry();
        String preservedEvidenceId = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE performance_entry
                SET evidence_type = 'CONTRACT', selected_file_id = 61,
                    selected_filename = 'intermediate-contract.pdf', selected_ext = 'pdf'
                WHERE id = ?
                """, entryId);
        jdbc.update("""
                INSERT INTO performance_evidence_backfill_map(legacy_source, entry_id, evidence_id, backfilled_at)
                VALUES ('PERFORMANCE_ENTRY', ?, ?, NULL)
                """, entryId, evidenceId);
        jdbc.update("""
                UPDATE performance_entry
                SET evidence_type = 'CERTIFICATE', selected_file_id = 62,
                    selected_filename = 'already-backfilled.pdf', selected_ext = 'pdf'
                WHERE id = ?
                """, preservedEntryId);
        jdbc.update("""
                INSERT INTO performance_evidence(
                    id, entry_id, evidence_type, selected_file_id, filename, file_ext, created_at, updated_at)
                VALUES (?, ?, 'CERTIFICATE', 62, 'already-backfilled.pdf', 'pdf', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, preservedEvidenceId, preservedEntryId);
        jdbc.update("""
                INSERT INTO performance_evidence_backfill_map(legacy_source, entry_id, evidence_id, backfilled_at)
                VALUES ('PERFORMANCE_ENTRY', ?, ?, NULL)
                """, preservedEntryId, preservedEvidenceId);
        jdbc.execute("ALTER TABLE performance_evidence_backfill_map DROP COLUMN backfilled_at");

        rerunSchema();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'PERFORMANCE_EVIDENCE_BACKFILL_MAP'
                  AND COLUMN_NAME = 'BACKFILLED_AT'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT evidence_id FROM performance_evidence_backfill_map
                WHERE legacy_source = 'PERFORMANCE_ENTRY' AND entry_id = ?
                """, String.class, entryId)).isEqualTo(evidenceId);
        assertThat(jdbc.queryForObject("""
                SELECT backfilled_at IS NOT NULL FROM performance_evidence_backfill_map
                WHERE legacy_source = 'PERFORMANCE_ENTRY' AND entry_id = ?
                """, Boolean.class, entryId)).isTrue();
        assertThat(repository.findByEntryId(entryId)).singleElement().satisfies(migrated -> {
            assertThat(migrated.id()).isEqualTo(evidenceId);
            assertThat(migrated.evidenceType()).isEqualTo(EvidenceType.CONTRACT);
            assertThat(migrated.selectedFileId()).isEqualTo(61L);
            assertThat(migrated.filename()).isEqualTo("intermediate-contract.pdf");
            assertThat(migrated.fileExt()).isEqualTo("pdf");
        });
        assertThat(repository.findByEntryId(preservedEntryId)).singleElement().satisfies(preserved -> {
            assertThat(preserved.id()).isEqualTo(preservedEvidenceId);
            assertThat(preserved.evidenceType()).isEqualTo(EvidenceType.CERTIFICATE);
            assertThat(preserved.selectedFileId()).isEqualTo(62L);
            assertThat(preserved.filename()).isEqualTo("already-backfilled.pdf");
        });

        rerunSchema();

        assertThat(repository.findByEntryId(entryId)).singleElement()
                .extracting(Evidence::id).isEqualTo(evidenceId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM performance_evidence_backfill_map
                WHERE legacy_source = 'PERFORMANCE_ENTRY' AND entry_id = ?
                """, Integer.class, entryId)).isOne();
        assertThat(repository.findByEntryId(preservedEntryId)).singleElement()
                .extracting(Evidence::id).isEqualTo(preservedEvidenceId);
    }

    private EvidenceInput companyFile(long fileId, EvidenceType type) {
        return new EvidenceInput(fileId, null, null, type);
    }

    private String entry() {
        String projectId = UUID.randomUUID().toString();
        String entryId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO performance_project(id, name, deadline) VALUES (?, 'project', DATE '2026-12-31')",
                projectId);
        jdbc.update("""
                INSERT INTO performance_entry(
                    id, project_id, business_name, business_period, contract_amount, client, kitc_status)
                VALUES (?, ?, 'business', '2026.01 ~ 2026.12', '100', 'client', 'NEEDED')
                """, entryId, projectId);
        return entryId;
    }

    private void driveFile(String id) {
        jdbc.update("""
                INSERT INTO performance_drive_file(
                    id, origin, company, drive_path, filename, file_ext, file_size)
                VALUES (?, 'origin', 'CNH', ?, 'drive.pdf', 'pdf', 10)
                """, id, "/" + id);
    }

    private void rerunSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    }
}
