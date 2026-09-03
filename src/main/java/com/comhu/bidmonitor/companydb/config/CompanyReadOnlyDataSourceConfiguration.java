package com.comhu.bidmonitor.companydb.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 기존 H2와 회사 DB를 명시적으로 분리한다.
 * Primary H2만 schema.sql 초기화와 기존 Repository에 사용되고, 회사 DB는 opt-in 조회에만 사용한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CompanyDatabaseProperties.class, SqlInitializationProperties.class})
public class CompanyReadOnlyDataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties bizAssistDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = {"dataSource", "bizAssistDataSource"})
    @Primary
    DataSource bizAssistDataSource(
            @Qualifier("bizAssistDataSourceProperties") DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder().build();
    }

    /** schema.sql initializer의 대상을 primary 후보 추론에 맡기지 않고 Biz Assist H2로 고정한다. */
    @Bean(name = "dataSourceScriptDatabaseInitializer")
    ApplicationDataSourceScriptDatabaseInitializer bizAssistDatabaseInitializer(
            @Qualifier("bizAssistDataSource") DataSource dataSource,
            SqlInitializationProperties properties
    ) {
        return new ApplicationDataSourceScriptDatabaseInitializer(dataSource, properties);
    }

    @Bean(name = "jdbcTemplate")
    @Primary
    JdbcTemplate bizAssistJdbcTemplate(@Qualifier("bizAssistDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "companyReadOnlyDataSource", destroyMethod = "close")
    @ConditionalOnProperty(name = "company-db.enabled", havingValue = "true")
    HikariDataSource companyReadOnlyDataSource(CompanyDatabaseProperties properties) {
        properties.requireConnectionSettings();

        HikariConfig config = new HikariConfig();
        config.setPoolName("company-read-only-pool");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(Math.max(1, Math.min(2, properties.getMaximumPoolSize())));
        config.setConnectionTimeout(properties.getConnectionTimeoutMillis());
        // Bean 생성 시 외부 DB에 접속하지 않고, 명시적인 진단 실행 시에만 연결한다.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean(name = "companyJdbcTemplate")
    @ConditionalOnProperty(name = "company-db.enabled", havingValue = "true")
    JdbcTemplate companyJdbcTemplate(
            @Qualifier("companyReadOnlyDataSource") DataSource dataSource,
            CompanyDatabaseProperties properties
    ) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
        jdbcTemplate.setMaxRows(Math.max(1, properties.getMaxRows()));
        return jdbcTemplate;
    }
}
