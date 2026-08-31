package com.comhu.bidmonitor.externalnotice.collector.pia;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 개인정보 포털의 현재 HTML을 확인하는 선택 실행형 테스트이다.
 * 기본 빌드에서는 외부 네트워크에 의존하지 않도록 환경변수를 지정한 경우에만 실행한다.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_PIA_INTEGRATION_TEST", matches = "true")
class PiaNoticeCollectorIntegrationTests {

    @Test
    void collectsCurrentPrivacyPortalNoticePage() {
        PiaNoticeCollector collector = new PiaNoticeCollector();

        List<CollectedNotice> notices = collector.collect();

        assertFalse(notices.isEmpty());
        assertTrue(notices.stream().allMatch(notice -> notice.getExternalId()
                .startsWith("PRIVACY_PORTAL:BBSMSTR_000000000001:")));
        assertTrue(notices.stream().allMatch(notice -> !notice.getTitle().isBlank()));
        assertTrue(notices.stream().allMatch(notice -> notice.getPublishedDate() != null));
        assertTrue(notices.stream().allMatch(notice -> !notice.getBody().isBlank()));
    }
}
