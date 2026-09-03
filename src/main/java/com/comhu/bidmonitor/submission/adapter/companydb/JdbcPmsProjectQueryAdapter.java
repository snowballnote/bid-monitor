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

    private final ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider;

    public JdbcPmsProjectQueryAdapter(
            @Qualifier("companyJdbcTemplate") ObjectProvider<JdbcTemplate> companyJdbcTemplateProvider
    ) {
        this.companyJdbcTemplateProvider = companyJdbcTemplateProvider;
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
}
