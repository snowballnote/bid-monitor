package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort.PmsProjectSummary;
import org.springframework.stereotype.Service;

import java.util.List;

/** 회사 PMS 사업 검색을 입력 검증 및 결과 제한과 함께 제공하는 application service다. */
@Service
public class SubmissionProjectSearchService {

    static final int RESULT_LIMIT = 20;
    private static final int MAX_QUERY_LENGTH = 100;

    private final PmsProjectQueryPort projectQueryPort;

    public SubmissionProjectSearchService(PmsProjectQueryPort projectQueryPort) {
        this.projectQueryPort = projectQueryPort;
    }

    public List<PmsProjectSummary> search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("사업 검색어를 입력해 주세요.");
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("사업 검색어는 100자 이하여야 합니다.");
        }
        return projectQueryPort.searchProjects(normalized, RESULT_LIMIT);
    }
}
