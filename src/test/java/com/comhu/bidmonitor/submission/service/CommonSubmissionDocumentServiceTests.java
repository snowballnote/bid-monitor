package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.CommonDocumentStatus;
import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.domain.CommonSubmissionDocument;
import com.comhu.bidmonitor.submission.domain.DocumentRefreshPolicy;
import com.comhu.bidmonitor.submission.persistence.CommonSubmissionDocumentRepository;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommonSubmissionDocumentServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);
    private final CommonSubmissionDocumentService service = new CommonSubmissionDocumentService(
            mock(CommonSubmissionDocumentRepository.class),
            mock(CompanyFileSearchPort.class),
            Clock.fixed(Instant.parse("2026-09-04T01:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void classifiesAdministrativeDocumentStatuses() {
        assertThat(status(DocumentRefreshPolicy.NONE, null, null, null, true))
                .isEqualTo(CommonDocumentStatus.AVAILABLE);
        assertThat(status(DocumentRefreshPolicy.NONE, null, null, null, false))
                .isEqualTo(CommonDocumentStatus.UNREGISTERED);
        assertThat(status(DocumentRefreshPolicy.PERIODIC, 3, TODAY.minusMonths(2), null, true))
                .isEqualTo(CommonDocumentStatus.AVAILABLE);
        assertThat(status(DocumentRefreshPolicy.PERIODIC, 3, TODAY.minusMonths(3), null, true))
                .isEqualTo(CommonDocumentStatus.REFRESH_RECOMMENDED);
        assertThat(status(DocumentRefreshPolicy.EXPIRATION_BASED, null, null, TODAY.plusDays(30), true))
                .isEqualTo(CommonDocumentStatus.EXPIRING_SOON);
        assertThat(status(DocumentRefreshPolicy.EXPIRATION_BASED, null, null, TODAY.minusDays(1), true))
                .isEqualTo(CommonDocumentStatus.EXPIRED);
        assertThat(status(DocumentRefreshPolicy.EXPIRATION_BASED, null, null, TODAY.plusDays(31), true))
                .isEqualTo(CommonDocumentStatus.AVAILABLE);
        assertThat(status(DocumentRefreshPolicy.EXPIRATION_BASED, null, null, null, true))
                .isEqualTo(CommonDocumentStatus.REFRESH_RECOMMENDED);
    }

    private CommonDocumentStatus status(
            DocumentRefreshPolicy policy,
            Integer interval,
            LocalDate issuedAt,
            LocalDate expiresAt,
            boolean registered
    ) {
        CommonSubmissionDocument document = CommonSubmissionDocument.builder()
                .documentType(CommonDocumentType.BUSINESS_REGISTRATION)
                .displayName("테스트 서류")
                .fileId(registered ? 1L : null)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .refreshPolicy(policy)
                .refreshIntervalMonths(interval)
                .active(true)
                .build();
        return service.determineStatus(document, TODAY);
    }
}
