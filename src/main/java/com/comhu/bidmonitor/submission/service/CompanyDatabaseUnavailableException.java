package com.comhu.bidmonitor.submission.service;

public class CompanyDatabaseUnavailableException extends RuntimeException {
    public CompanyDatabaseUnavailableException() {
        super("회사 DB 조회 기능이 활성화되지 않았습니다.");
    }
}
