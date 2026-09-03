package com.comhu.bidmonitor.submission.adapter.companydb;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort.CompanyFileMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 명시적으로 opt-in한 경우에만 PostgreSQL_DEV의 public.files 검색 SQL과 타입 매핑을 검증한다.
 * storage_path를 조회하지 않으며, 출력하는 파일명은 일부 마스킹한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:company-file-search-live;DB_CLOSE_DELAY=-1",
        "company-db.enabled=true",
        "external-notice.scheduler.enabled=false",
        "logging.level.com.zaxxer.hikari=OFF",
        "logging.level.org.postgresql=OFF",
        "logging.level.org.springframework.jdbc=OFF"
})
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_FILE_SEARCH_LIVE_TEST", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_PASSWORD", matches = ".+")
class JdbcCompanyFileSearchAdapterLiveTests {

    private static final String SEARCH_KEYWORD = "증명서";
    private static final int MAX_RESULTS = 5;

    @Autowired
    private JdbcCompanyFileSearchAdapter adapter;

    @Autowired
    @Qualifier("companyJdbcTemplate")
    private JdbcTemplate companyJdbcTemplate;

    @Autowired
    @Qualifier("companyReadOnlyDataSource")
    private DataSource companyDataSource;

    @Test
    void searchesActualActiveFilesWithoutReadingStoragePath() {
        try (Connection connection = companyDataSource.getConnection()) {
            assertTrue(connection.isReadOnly(), "회사 DB connection은 read-only여야 합니다.");

            List<CompanyFileMetadata> results = adapter.searchByKeywords(List.of(SEARCH_KEYWORD), MAX_RESULTS);

            assertFalse(results.isEmpty(), "일반 문서 키워드에 해당하는 파일 metadata가 필요합니다.");
            assertTrue(results.size() <= MAX_RESULTS);
            assertNewestFirst(results);

            print("LIVE_COMPANY_FILE_SEARCH_COUNT", results.size());
            for (int index = 0; index < results.size(); index++) {
                CompanyFileMetadata file = results.get(index);
                assertNotNull(file.fileId(), "file_id 매핑 결과가 필요합니다.");

                FileState state = companyJdbcTemplate.queryForObject(
                        "SELECT file_status, is_dir FROM public.files WHERE file_id = ?",
                        (resultSet, rowNumber) -> new FileState(
                                resultSet.getInt("file_status"),
                                resultSet.getBoolean("is_dir")
                        ),
                        file.fileId()
                );
                assertNotNull(state);
                assertTrue(state.fileStatus() == 0, "삭제 또는 비활성 파일이 포함되면 안 됩니다.");
                assertFalse(state.directory(), "디렉터리가 포함되면 안 됩니다.");

                print("LIVE_COMPANY_FILE_SEARCH_ROW",
                        "INDEX=" + (index + 1)
                                + "|FILE_ID_PRESENT=" + (file.fileId() != null)
                                + "|PUBLIC_ID_PRESENT=" + (file.publicId() != null)
                                + "|ORIGINAL_FILENAME_MASKED=" + maskFilename(file.originalFilename())
                                + "|FILE_EXT=" + safe(file.fileExt())
                                + "|FILE_STATUS=" + state.fileStatus()
                                + "|FILE_MODIFIED_AT=" + safe(file.fileModifiedAt()));
            }
            print("LIVE_COMPANY_FILE_SEARCH_ORDERED_NEWEST_FIRST", true);
        } catch (Exception exception) {
            // JDBC 예외에는 endpoint가 포함될 수 있으므로 원인 예외를 테스트 출력으로 전달하지 않는다.
            fail("회사 파일 후보 live 검증에 실패했습니다. 로컬에서 DB 연결과 SELECT 권한을 확인하세요.");
        }
    }

    private void assertNewestFirst(List<CompanyFileMetadata> results) {
        Instant previous = null;
        for (CompanyFileMetadata file : results) {
            Instant current = file.fileModifiedAt() != null ? file.fileModifiedAt() : file.updatedAt();
            if (previous != null && current != null) {
                assertFalse(current.isAfter(previous), "후보는 최신 수정일 우선이어야 합니다.");
            }
            if (current != null) {
                previous = current;
            }
        }
    }

    private String maskFilename(String filename) {
        String value = safe(filename);
        int dot = value.lastIndexOf('.');
        String base = dot > 0 ? value.substring(0, dot) : value;
        String extension = dot > 0 ? value.substring(dot) : "";
        if (base.length() <= 2) {
            return "**" + extension;
        }
        return base.charAt(0) + "*".repeat(Math.min(8, base.length() - 2))
                + base.charAt(base.length() - 1) + extension;
    }

    private String safe(Object value) {
        return value == null ? "" : value.toString();
    }

    private void print(String key, Object value) {
        System.out.println(key + "=" + value);
    }

    private record FileState(int fileStatus, boolean directory) {
    }
}
