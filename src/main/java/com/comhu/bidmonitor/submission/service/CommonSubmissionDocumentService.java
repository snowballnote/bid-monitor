package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.CommonDocumentStatus;
import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.domain.CommonSubmissionDocument;
import com.comhu.bidmonitor.submission.domain.DocumentRefreshPolicy;
import com.comhu.bidmonitor.submission.persistence.CommonSubmissionDocumentRepository;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 공통 제출서류의 현재 사용본 등록과 행정 기준에 따른 상태 판정을 담당한다. */
@Service
public class CommonSubmissionDocumentService {

    private static final int EXPIRING_SOON_DAYS = 30;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final CommonSubmissionDocumentRepository repository;
    private final CompanyFileSearchPort fileSearchPort;
    private final Clock clock;

    public CommonSubmissionDocumentService(
            CommonSubmissionDocumentRepository repository,
            CompanyFileSearchPort fileSearchPort,
            Clock clock
    ) {
        this.repository = repository;
        this.fileSearchPort = fileSearchPort;
        this.clock = clock;
    }

    public List<ManagedDocument> findAll() {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        return repository.findAllActive().stream()
                .map(document -> new ManagedDocument(document, determineStatus(document, today)))
                .toList();
    }

    @Transactional
    public ManagedDocument updateCurrentReference(
            CommonDocumentType documentType,
            Long fileId,
            LocalDate issuedAt,
            LocalDate expiresAt
    ) {
        if (documentType == null || fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("공통서류 유형과 활성 파일 ID가 필요합니다.");
        }
        CommonSubmissionDocument current = repository.findByDocumentType(documentType)
                .filter(CommonSubmissionDocument::isActive)
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않거나 비활성화된 공통서류 유형입니다."));
        validateDates(current.getRefreshPolicy(), issuedAt, expiresAt);

        CompanyFileSearchPort.CompanyFileMetadata file = fileSearchPort.findActiveFileById(fileId)
                .orElseThrow(() -> new InvalidSubmissionSelectionException("선택한 활성 파일을 찾을 수 없습니다."));
        CommonSubmissionDocument updated = repository.updateCurrentReference(current.toBuilder()
                .fileId(file.fileId())
                .filePublicId(file.publicId())
                .originalFilename(file.originalFilename())
                .fileExt(file.fileExt())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .updatedAt(clock.instant())
                .build());
        return new ManagedDocument(
                updated,
                determineStatus(updated, LocalDate.now(clock.withZone(BUSINESS_ZONE)))
        );
    }

    CommonDocumentStatus determineStatus(CommonSubmissionDocument document, LocalDate today) {
        if (document.getFileId() == null) {
            return CommonDocumentStatus.UNREGISTERED;
        }
        if (document.getRefreshPolicy() == DocumentRefreshPolicy.NONE) {
            return CommonDocumentStatus.AVAILABLE;
        }
        if (document.getRefreshPolicy() == DocumentRefreshPolicy.PERIODIC) {
            if (document.getIssuedAt() == null || document.getRefreshIntervalMonths() == null) {
                return CommonDocumentStatus.REFRESH_RECOMMENDED;
            }
            return document.getIssuedAt().plusMonths(document.getRefreshIntervalMonths()).isAfter(today)
                    ? CommonDocumentStatus.AVAILABLE
                    : CommonDocumentStatus.REFRESH_RECOMMENDED;
        }
        if (document.getExpiresAt() == null) {
            return CommonDocumentStatus.REFRESH_RECOMMENDED;
        }
        if (document.getExpiresAt().isBefore(today)) {
            return CommonDocumentStatus.EXPIRED;
        }
        if (!document.getExpiresAt().isAfter(today.plusDays(EXPIRING_SOON_DAYS))) {
            return CommonDocumentStatus.EXPIRING_SOON;
        }
        return CommonDocumentStatus.AVAILABLE;
    }

    private void validateDates(
            DocumentRefreshPolicy policy,
            LocalDate issuedAt,
            LocalDate expiresAt
    ) {
        if (issuedAt != null && expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("만료일은 발급일보다 빠를 수 없습니다.");
        }
        if (policy == DocumentRefreshPolicy.NONE && (issuedAt != null || expiresAt != null)) {
            throw new IllegalArgumentException("유효기간이 없는 서류에는 발급일/만료일을 관리하지 않습니다.");
        }
        if (policy == DocumentRefreshPolicy.PERIODIC && expiresAt != null) {
            throw new IllegalArgumentException("주기 갱신 서류에는 만료일 대신 발급일을 사용합니다.");
        }
    }

    public record ManagedDocument(CommonSubmissionDocument document, CommonDocumentStatus status) {
    }
}
