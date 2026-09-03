package com.comhu.bidmonitor.submission.adapter.companydb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcPmsProjectQueryAdapterTests {

    @Test
    void searchesPmsProjectWithBoundParametersAndHardResultLimit() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        var adapter = new JdbcPmsProjectQueryAdapter(provider);

        assertThat(adapter.searchProjects(" 100%_사업 ", 100)).isEmpty();

        assertThat(jdbcTemplate.sql.stripLeading()).startsWith("SELECT");
        assertThat(jdbcTemplate.sql).contains("FROM pms.project", "notice_name", "bid_notice_no",
                "internal_biz_no", "project_code", "organization_name", "LIMIT ?");
        assertThat(jdbcTemplate.sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "MERGE ");
        assertThat(jdbcTemplate.arguments).hasSize(6);
        assertThat(jdbcTemplate.arguments[0]).isEqualTo("%100\\%\\_사업%");
        assertThat(jdbcTemplate.arguments[5]).isEqualTo(20);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] arguments;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return List.of();
        }
    }
}
