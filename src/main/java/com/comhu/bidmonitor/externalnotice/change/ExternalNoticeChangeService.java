package com.comhu.bidmonitor.externalnotice.change;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** externalId와 fingerprint를 비교해 현재값 저장 또는 확인시각 갱신을 결정한다. */
@Service
public class ExternalNoticeChangeService {

    private final ExternalNoticeRepository repository;

    public ExternalNoticeChangeService(ExternalNoticeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public NoticeChangeResult process(ExternalNotice currentNotice) {
        validate(currentNotice);

        Optional<ExternalNotice> existingNotice = repository.findByExternalId(currentNotice.getExternalId());
        if (existingNotice.isEmpty()) {
            Objects.requireNonNull(
                    currentNotice.getFirstSeenAt(),
                    "신규 공지의 firstSeenAt은 null일 수 없습니다."
            );
            ExternalNotice savedNotice = repository.save(currentNotice);
            return createResult(NoticeChangeType.NEW, savedNotice, null, currentNotice.getFingerprint());
        }

        ExternalNotice existing = existingNotice.get();
        if (Objects.equals(existing.getFingerprint(), currentNotice.getFingerprint())) {
            repository.updateLastSeenAt(existing.getId(), currentNotice.getLastSeenAt());
            return createResult(
                    NoticeChangeType.UNCHANGED,
                    existing,
                    existing.getFingerprint(),
                    currentNotice.getFingerprint()
            );
        }

        ExternalNotice updatedNotice = repository.updateContent(existing.getId(), currentNotice);
        return createResult(
                NoticeChangeType.UPDATED,
                updatedNotice,
                existing.getFingerprint(),
                currentNotice.getFingerprint()
        );
    }

    private NoticeChangeResult createResult(
            NoticeChangeType changeType,
            ExternalNotice notice,
            String previousFingerprint,
            String currentFingerprint
    ) {
        return NoticeChangeResult.builder()
                .externalId(notice.getExternalId())
                .changeType(changeType)
                .noticeId(notice.getId())
                .previousFingerprint(previousFingerprint)
                .currentFingerprint(currentFingerprint)
                .build();
    }

    private void validate(ExternalNotice notice) {
        Objects.requireNonNull(notice, "판정할 외부공지는 null일 수 없습니다.");
        Objects.requireNonNull(notice.getExternalId(), "externalId는 null일 수 없습니다.");
        Objects.requireNonNull(notice.getFingerprint(), "fingerprint는 null일 수 없습니다.");
        Objects.requireNonNull(notice.getLastSeenAt(), "lastSeenAt은 null일 수 없습니다.");
    }
}
