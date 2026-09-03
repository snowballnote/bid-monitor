package com.comhu.bidmonitor.companydb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 회사 DB 접속값과 조회 제한을 코드에서 분리하고 기본 상태를 비활성으로 유지한다. */
@Getter
@Setter
@ConfigurationProperties("company-db")
public class CompanyDatabaseProperties {

    private boolean enabled = false;
    private String url = "";
    private String username = "";
    private String password = "";
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 2;
    private long connectionTimeoutMillis = 5_000;
    private int queryTimeoutSeconds = 10;
    private int maxRows = 500;

    void requireConnectionSettings() {
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException("회사 DB 연결 환경변수가 모두 설정되어야 합니다.");
        }
    }
}
