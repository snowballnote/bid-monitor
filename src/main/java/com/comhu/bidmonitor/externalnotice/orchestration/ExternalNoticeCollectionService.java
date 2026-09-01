package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.externalnotice.change.ExternalNoticeChangeService;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.classifier.PiaClassificationResult;
import com.comhu.bidmonitor.externalnotice.classifier.PiaNoticeClassifier;
import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollector;
import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import com.comhu.bidmonitor.externalnotice.fingerprint.NoticeFingerprintGenerator;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 수집·분류·fingerprint·변경 판정을 순서대로 조합하는 수동 실행 진입점이다. */
@Service
public class ExternalNoticeCollectionService {

    private final ExternalNoticeCollector collector;
    private final PiaNoticeClassifier classifier;
    private final NoticeFingerprintGenerator fingerprintGenerator;
    private final ExternalNoticeChangeService changeService;
    private final Clock clock;

    public ExternalNoticeCollectionService(
            ExternalNoticeCollector collector,
            PiaNoticeClassifier classifier,
            NoticeFingerprintGenerator fingerprintGenerator,
            ExternalNoticeChangeService changeService,
            Clock clock
    ) {
        this.collector = collector;
        this.classifier = classifier;
        this.fingerprintGenerator = fingerprintGenerator;
        this.changeService = changeService;
        this.clock = clock;
    }

    public ExternalNoticeCollectionResult runCollection() {
        // 한 실행에서 모든 공지가 같은 관측 시각을 갖도록 Clock은 정확히 한 번만 읽는다.
        Instant executionTime = clock.instant();

        // 목록 또는 상세 수집 자체가 실패하면 이후 단계가 신뢰할 수 없으므로 예외를 그대로 전달한다.
        List<CollectedNotice> collectedNotices = collector.collect();
        List<NoticeChangeResult> noticeResults = new ArrayList<>();
        List<NoticeProcessingFailure> failures = new ArrayList<>();
        int piaRelatedCount = 0;

        for (CollectedNotice collectedNotice : collectedNotices) {
            try {
                PiaClassificationResult classification = classifier.classify(collectedNotice);
                String fingerprint = fingerprintGenerator.generate(collectedNotice);
                ExternalNotice notice = convert(
                        collectedNotice,
                        classification,
                        fingerprint,
                        executionTime
                );
                NoticeChangeResult changeResult = changeService.process(notice);
                noticeResults.add(changeResult);
                if (classification.isPiaRelated()) {
                    piaRelatedCount++;
                }
            } catch (RuntimeException e) {
                failures.add(createFailure(collectedNotice, e));
            }
        }

        return createResult(collectedNotices.size(), piaRelatedCount, noticeResults, failures);
    }

    private ExternalNotice convert(
            CollectedNotice collectedNotice,
            PiaClassificationResult classification,
            String fingerprint,
            Instant executionTime
    ) {
        ExternalNotice.ExternalNoticeBuilder builder = ExternalNotice.builder()
                .sourceCode(collectedNotice.getSource().getCode())
                .externalId(collectedNotice.getExternalId())
                .sourceNoticeId(collectedNotice.getSourceNoticeId())
                .title(collectedNotice.getTitle())
                .publishedDate(collectedNotice.getPublishedDate())
                .detailUrl(collectedNotice.getDetailUrl())
                .body(collectedNotice.getBody())
                .piaRelated(classification.isPiaRelated())
                .matchedKeywords(classification.getMatchedKeywords())
                .classificationReason(classification.getReason())
                .fingerprint(fingerprint)
                .firstSeenAt(executionTime)
                .lastSeenAt(executionTime);

        for (NoticeAttachment attachment : collectedNotice.getAttachments()) {
            builder.attachment(ExternalNoticeAttachment.builder()
                    .fileName(attachment.getFileName())
                    .fileUrl(attachment.getFileUrl())
                    .build());
        }
        return builder.build();
    }

    private NoticeProcessingFailure createFailure(CollectedNotice notice, RuntimeException exception) {
        return NoticeProcessingFailure.builder()
                .externalId(notice == null ? null : notice.getExternalId())
                .title(notice == null ? null : notice.getTitle())
                .errorType(exception.getClass().getSimpleName())
                .errorMessage(exception.getMessage())
                .build();
    }

    private ExternalNoticeCollectionResult createResult(
            int collectedCount,
            int piaRelatedCount,
            List<NoticeChangeResult> noticeResults,
            List<NoticeProcessingFailure> failures
    ) {
        int newCount = count(noticeResults, NoticeChangeType.NEW);
        int updatedCount = count(noticeResults, NoticeChangeType.UPDATED);
        int unchangedCount = count(noticeResults, NoticeChangeType.UNCHANGED);
        return ExternalNoticeCollectionResult.builder()
                .collectedCount(collectedCount)
                .newCount(newCount)
                .updatedCount(updatedCount)
                .unchangedCount(unchangedCount)
                .piaRelatedCount(piaRelatedCount)
                .failedCount(failures.size())
                .noticeResults(noticeResults)
                .failures(failures)
                .build();
    }

    private int count(List<NoticeChangeResult> results, NoticeChangeType changeType) {
        return (int) results.stream()
                .filter(result -> result.getChangeType() == changeType)
                .count();
    }
}
