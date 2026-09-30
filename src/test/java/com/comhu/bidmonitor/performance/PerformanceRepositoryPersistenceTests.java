package com.comhu.bidmonitor.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:performance-repositories;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class PerformanceRepositoryPersistenceTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PerformanceDriveFileRepository driveFileRepository;

    @Autowired
    private DriveFileIndexRepository driveFileIndexRepository;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM drive_file_index");
        jdbcTemplate.update("DELETE FROM drive_index_root_state");
        jdbcTemplate.update("DELETE FROM performance_drive_file");
    }

    @Test
    void duplicateDriveLocatorKeepsIdentityAndUpdatesMetadata() {
        Instant firstModified = Instant.parse("2026-09-30T01:02:03Z");
        Instant updatedModified = firstModified.plusSeconds(60);
        String firstId = driveFileRepository.register("drive", "CNH",
                new FmsDrivePort.Item("old.pdf", "/evidence/file.pdf", false, 10, firstModified),
                "first-id").id();

        PerformanceDriveFileRepository.Reference updated = driveFileRepository.register("drive", "CNH",
                new FmsDrivePort.Item("new.pdf", "/evidence/file.pdf", false, 20, updatedModified),
                "ignored-id");

        assertEquals(firstId, updated.id());
        assertEquals("new.pdf", updated.filename());
        assertEquals(20, updated.size());
        assertEquals(updatedModified, updated.modified());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_drive_file", Integer.class));
    }

    @Test
    void repeatedIndexStartUpsertsOneRootState() {
        driveFileIndexRepository.started("drive", "CNH", "/evidence");
        driveFileIndexRepository.started("drive", "CNH", "/evidence");

        DriveFileIndexRepository.State state = driveFileIndexRepository.state("drive", "CNH", "/evidence");
        assertEquals("REFRESHING", state.status());
        assertNotNull(state.lastAttemptAt());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM drive_index_root_state", Integer.class));
    }
}
