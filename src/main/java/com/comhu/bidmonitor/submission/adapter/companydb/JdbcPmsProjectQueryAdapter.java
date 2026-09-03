package com.comhu.bidmonitor.submission.adapter.companydb;

import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** companyJdbcTemplate만 사용하여 pms schema를 SELECT로 조회한다. */
@Repository
public class JdbcPmsProjectQueryAdapter implements PmsProjectQueryPort {

    private static final int MAX_SEARCH_RESULTS = 20;

    /*
     * PostgreSQL_DEV의 PMS 버전별 발주기관 컬럼명을 흡수한다. to_jsonb(row)의 키 조회는
     * 존재하지 않는 후보 컬럼도 안전하게 null로 처리하며, 실제 값이나 내부 경로는 반환하지 않는다.
     */
    private static final String ORGANIZATION_NAME = """
            COALESCE(
                NULLIF(to_jsonb(project_row) ->> 'ordering_organization_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'ordering_org_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'order_org_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'ordering_institution_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'order_institution_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'client_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'customer_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'organization_name', ''),
                NULLIF(to_jsonb(project_row) ->> 'agency_name', '')
            )
            """;

    private final ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider;

    public JdbcPmsProjectQueryAdapter(
            @Qualifier("companyJdbcTemplate") ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider
    ) {
        this.companyJdbcTemplateProvider = companyJdbcTemplateProvider;
    }

    @Override
    public List<PmsProjectSummary> searchProjects(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_SEARCH_RESULTS));
        String pattern = "%" + escapeLikePattern(normalized.toLowerCase()) + "%";
        String sql = """
                SELECT project_id, notice_name, bid_notice_no,
                       %s AS organization_name
                FROM pms.project project_row
                WHERE LOWER(COALESCE(notice_name, '')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(bid_notice_no, '')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(internal_biz_no, '')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(project_code, '')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(%s, '')) LIKE ? ESCAPE '\\'
                ORDER BY project_id DESC
                LIMIT ?
                """.formatted(ORGANIZATION_NAME, ORGANIZATION_NAME);
        return companyJdbcTemplate().query(
                sql,
                (resultSet, rowNumber) -> new PmsProjectSummary(
                        resultSet.getLong("project_id"),
                        resultSet.getString("notice_name"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("bid_notice_no")
                ),
                pattern, pattern, pattern, pattern, pattern, safeLimit
        );
    }

    @Override
    public Optional<PmsProject> findProjectById(Long projectId) {
        return companyJdbcTemplate().query(
                """
                        SELECT project_id, public_id, project_code, internal_biz_no,
                               notice_name, bid_notice_no
                        FROM pms.project
                        WHERE project_id = ?
                        """,
                (resultSet, rowNumber) -> new PmsProject(
                        resultSet.getLong("project_id"),
                        resultSet.getObject("public_id", UUID.class),
                        resultSet.getString("project_code"),
                        resultSet.getString("internal_biz_no"),
                        resultSet.getString("notice_name"),
                        resultSet.getString("bid_notice_no")
                ),
                projectId
        ).stream().findFirst();
    }

    @Override
    public List<PmsRfpItem> findRfpItems(Long projectId) {
        return companyJdbcTemplate().query(
                """
                        SELECT rfp_item_id, category, content, source_doc_ref
                        FROM pms.project_rfp_item
                        WHERE project_id = ?
                        ORDER BY rfp_item_id
                        LIMIT 200
                        """,
                (resultSet, rowNumber) -> new PmsRfpItem(
                        resultSet.getLong("rfp_item_id"),
                        resultSet.getString("category"),
                        resultSet.getString("content"),
                        resultSet.getString("source_doc_ref")
                ),
                projectId
        );
    }

    private JdbcTemplate companyJdbcTemplate() {
        JdbcTemplate jdbcTemplate = companyJdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new CompanyDatabaseUnavailableException();
        }
        return jdbcTemplate;
    }

    private String escapeLikePattern(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
