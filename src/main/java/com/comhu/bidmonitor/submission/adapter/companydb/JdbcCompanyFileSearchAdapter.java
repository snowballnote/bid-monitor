package com.comhu.bidmonitor.submission.adapter.companydb;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** public.files에서 활성 파일 metadata만 검색하며 storage_path나 파일 본문은 조회하지 않는다. */
@Repository
public class JdbcCompanyFileSearchAdapter implements CompanyFileSearchPort {

    private static final String SAFE_COLUMNS = """
            file_id, public_id, original_filename, file_ext, file_modified_at, updated_at
            """;

    private final ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider;

    public JdbcCompanyFileSearchAdapter(
            @Qualifier("companyJdbcTemplate") ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider
    ) {
        this.companyJdbcTemplateProvider = companyJdbcTemplateProvider;
    }

    @Override
    public List<CompanyFileMetadata> searchByKeywords(List<String> keywords, int limit) {
        List<String> normalizedKeywords = keywords == null ? List.of() : keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .limit(6)
                .toList();
        if (normalizedKeywords.isEmpty()) {
            return List.of();
        }

        String conditions = String.join(" OR ", normalizedKeywords.stream()
                .map(ignored -> "(lower(coalesce(original_filename, '')) LIKE ? ESCAPE '\\' "
                        + "OR lower(coalesce(file_ext, '')) LIKE ? ESCAPE '\\')")
                .toList());
        String sql = "SELECT " + SAFE_COLUMNS + " FROM public.files "
                + "WHERE file_status = ? AND is_dir = ? AND (" + conditions + ") "
                + "ORDER BY COALESCE(file_modified_at, updated_at) DESC NULLS LAST, file_id DESC LIMIT ?";

        List<Object> parameters = new ArrayList<>();
        parameters.add(0);
        parameters.add(false);
        for (String keyword : normalizedKeywords) {
            String pattern = "%" + escapeLike(keyword) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        parameters.add(Math.max(1, Math.min(100, limit)));
        return companyJdbcTemplate().query(sql, this::map, parameters.toArray());
    }

    @Override
    public List<CompanyFileMetadata> searchPerformanceEvidence(String businessName, String client, int limit) {
        if (businessName == null || businessName.isBlank() || client == null || client.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>(List.of(businessName.trim().split("\\s+")));
        terms.addAll(List.of(client.trim().split("\\s+")));
        terms = terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).distinct().toList();
        String conditions = String.join(" AND ", terms.stream()
                .map(term -> "lower(coalesce(original_filename, '')) LIKE ? ESCAPE '\\'").toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(0);
        parameters.add(false);
        terms.forEach(term -> parameters.add("%" + escapeLike(term) + "%"));
        parameters.add(Math.max(1, Math.min(100, limit)));
        return companyJdbcTemplate().query("SELECT " + SAFE_COLUMNS + " FROM public.files "
                + "WHERE file_status = ? AND is_dir = ? AND " + conditions
                + " ORDER BY COALESCE(file_modified_at, updated_at) DESC NULLS LAST, file_id DESC LIMIT ?",
                this::map, parameters.toArray());
    }

    @Override
    public Optional<CompanyFileMetadata> findActiveFileById(Long fileId) {
        return companyJdbcTemplate().query(
                "SELECT " + SAFE_COLUMNS + " FROM public.files "
                        + "WHERE file_id = ? AND file_status = ? AND is_dir = ?",
                this::map,
                fileId,
                0,
                false
        ).stream().findFirst();
    }

    private CompanyFileMetadata map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CompanyFileMetadata(
                resultSet.getLong("file_id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("original_filename"),
                resultSet.getString("file_ext"),
                readInstant(resultSet, "file_modified_at"),
                readInstant(resultSet, "updated_at")
        );
    }

    private Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private JdbcTemplate companyJdbcTemplate() {
        JdbcTemplate jdbcTemplate = companyJdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new CompanyDatabaseUnavailableException();
        }
        return jdbcTemplate;
    }
}
