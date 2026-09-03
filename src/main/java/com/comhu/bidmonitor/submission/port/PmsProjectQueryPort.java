package com.comhu.bidmonitor.submission.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL pms schema에서 사업과 RFP 항목을 SELECT로만 조회하는 outbound port다. */
public interface PmsProjectQueryPort {

    List<PmsProjectSummary> searchProjects(String query, int limit);

    Optional<PmsProject> findProjectById(Long projectId);

    List<PmsRfpItem> findRfpItems(Long projectId);

    record PmsProject(
            Long projectId,
            UUID publicId,
            String projectCode,
            String internalBizNo,
            String noticeName,
            String bidNoticeNo
    ) {
    }

    /** 사업 선택 화면에 필요한 최소 정보만 회사 DB 경계 밖으로 전달한다. */
    record PmsProjectSummary(
            Long projectId,
            String noticeName,
            String organizationName,
            String bidNoticeNo
    ) {
    }

    record PmsRfpItem(Long id, String category, String content, String sourceDocumentReference) {
    }
}
