package com.comhu.bidmonitor.companydb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 외부 접속 없이 primary H2와 회사 DB용 bean 및 schema 초기화가 분리되는지 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:biz-assist-isolation;DB_CLOSE_DELAY=-1",
        "company-db.enabled=true",
        "company-db.url=jdbc:h2:mem:company-isolation;DB_CLOSE_DELAY=-1",
        "company-db.username=sa",
        "company-db.password=test-only",
        "company-db.driver-class-name=org.h2.Driver"
})
class CompanyReadOnlyDataSourceConfigurationTests {

    private final DataSource primaryDataSource;
    private final DataSource companyDataSource;
    private final JdbcTemplate primaryJdbcTemplate;
    private final JdbcTemplate companyJdbcTemplate;

    CompanyReadOnlyDataSourceConfigurationTests(
            @Qualifier("bizAssistDataSource") DataSource primaryDataSource,
            @Qualifier("companyReadOnlyDataSource") DataSource companyDataSource,
            @Qualifier("jdbcTemplate") JdbcTemplate primaryJdbcTemplate,
            @Qualifier("companyJdbcTemplate") JdbcTemplate companyJdbcTemplate
    ) {
        this.primaryDataSource = primaryDataSource;
        this.companyDataSource = companyDataSource;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.companyJdbcTemplate = companyJdbcTemplate;
    }

    @Test
    void keepsCompanyDataSourceAndSchemaInitializationSeparateFromPrimaryH2() {
        assertNotSame(primaryDataSource, companyDataSource);
        assertSame(primaryDataSource, primaryJdbcTemplate.getDataSource());
        assertSame(companyDataSource, companyJdbcTemplate.getDataSource());

        assertEquals(1, tableCount(primaryJdbcTemplate, "EXTERNAL_NOTICE"));
        assertEquals(0, tableCount(companyJdbcTemplate, "EXTERNAL_NOTICE"));
    }

    private int tableCount(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                Integer.class,
                tableName
        );
        return count == null ? 0 : count;
    }
}
