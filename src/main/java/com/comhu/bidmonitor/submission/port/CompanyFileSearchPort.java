package com.comhu.bidmonitor.submission.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** public.files를 읽되 NAS 경로를 도메인으로 반출하지 않는 read-only 검색 port다. */
public interface CompanyFileSearchPort {

    List<CompanyFileMetadata> searchByKeywords(List<String> keywords, int limit);

    /** 실적 전용: 사업명 토큰과 발주처를 모두 포함하는 파일명만 추천한다. */
    default List<CompanyFileMetadata> searchPerformanceEvidence(String businessName, String client, int limit) {
        return List.of();
    }

    Optional<CompanyFileMetadata> findActiveFileById(Long fileId);

    record CompanyFileMetadata(
            Long fileId,
            UUID publicId,
            String originalFilename,
            String fileExt,
            Instant fileModifiedAt,
            Instant updatedAt
    ) {
    }
}
