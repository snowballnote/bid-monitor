package com.comhu.bidmonitor.submission.adapter.companydb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcCompanyFileSearchAdapterTests {

    private JdbcCompanyFileSearchAdapter adapter;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:company-file-search;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS public.files");
        jdbcTemplate.execute("""
                CREATE TABLE public.files (
                    file_id BIGINT PRIMARY KEY,
                    public_id UUID NOT NULL,
                    original_filename VARCHAR(500),
                    file_ext VARCHAR(30),
                    file_status INTEGER NOT NULL,
                    is_dir BOOLEAN NOT NULL,
                    file_modified_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE
                )
                """);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        adapter = new JdbcCompanyFileSearchAdapter(provider);
    }

    @Test
    void searchesOnlyActiveNonDirectoryMetadataAndOrdersNewestFirst() {
        insert(1L, "사업자등록증_이전.pdf", 0, false, "2026-08-01T00:00:00Z");
        insert(2L, "사업자등록증_최신.pdf", 0, false, "2026-09-01T00:00:00Z");
        insert(3L, "사업자등록증_삭제.pdf", 1, false, "2026-09-02T00:00:00Z");
        insert(4L, "사업자등록증_폴더", 0, true, "2026-09-03T00:00:00Z");

        var results = adapter.searchByKeywords(java.util.List.of("사업자등록증"), 50);

        assertThat(results).extracting(result -> result.fileId()).containsExactly(2L, 1L);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.originalFilename()).contains("사업자등록증");
            assertThat(result.publicId()).isNotNull();
        });
    }

    @Test
    void resolvesOnlyAnActiveSelectableFileById() {
        insert(10L, "실적증명서.pdf", 0, false, "2026-09-01T00:00:00Z");
        insert(11L, "삭제된 실적증명서.pdf", 1, false, "2026-09-02T00:00:00Z");

        assertThat(adapter.findActiveFileById(10L)).isPresent();
        assertThat(adapter.findActiveFileById(11L)).isEmpty();
    }

    private void insert(Long id, String filename, int status, boolean directory, String modifiedAt) {
        Instant instant = Instant.parse(modifiedAt);
        jdbcTemplate.update(
                """
                        INSERT INTO public.files (
                            file_id, public_id, original_filename, file_ext, file_status,
                            is_dir, file_modified_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                UUID.randomUUID(),
                filename,
                "pdf",
                status,
                directory,
                Timestamp.from(instant),
                Timestamp.from(instant)
        );
    }
}
