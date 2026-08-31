package com.comhu.bidmonitor.externalnotice.collector.pia;

import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollectionException;
import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import com.comhu.bidmonitor.externalnotice.domain.NoticeSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiaNoticeCollectorTests {

    @Test
    void collectsOrdinaryAndPinnedNoticesFromFixtures() {
        PiaNoticeCollector collector = new PiaNoticeCollector(this::loadFixtureForUri);

        List<CollectedNotice> notices = collector.collect();

        // 목록 fixture에는 같은 일반 공지가 두 번 있지만 bbscttNo 기준으로 한 번만 상세조회한다.
        assertEquals(2, notices.size());

        CollectedNotice pinnedNotice = notices.getFirst();
        assertEquals(NoticeSource.PRIVACY_PORTAL, pinnedNotice.getSource());
        assertEquals(
                "PRIVACY_PORTAL:BBSMSTR_000000000001:20878",
                pinnedNotice.getExternalId()
        );
        assertEquals("20878", pinnedNotice.getSourceNoticeId());
        assertEquals("2026년 개인정보 영향평가 계속교육 일정 안내", pinnedNotice.getTitle());
        assertEquals(LocalDate.of(2026, 4, 3), pinnedNotice.getPublishedDate());
        assertTrue(pinnedNotice.getDetailUrl().endsWith("bbscttNo=20878"));
        assertTrue(pinnedNotice.getBody().contains("계속교육 일정을 안내"));
        assertTrue(pinnedNotice.getAttachments().isEmpty());

        CollectedNotice ordinaryNotice = notices.get(1);
        assertEquals(
                "PRIVACY_PORTAL:BBSMSTR_000000000001:20907",
                ordinaryNotice.getExternalId()
        );
        assertEquals("2026년 개인정보 영향평가 전문교육(하반기) 안내", ordinaryNotice.getTitle());
        assertEquals(LocalDate.of(2026, 6, 17), ordinaryNotice.getPublishedDate());
        assertTrue(ordinaryNotice.getBody().contains("교육 신청기간과 자격요건"));
        assertEquals(1, ordinaryNotice.getAttachments().size());

        NoticeAttachment attachment = ordinaryNotice.getAttachments().getFirst();
        assertEquals(
                "(최종)공지사항_26년 개인정보 영향평가 전문교육(하반기)_포털_붙임.hwp",
                attachment.getFileName()
        );
        assertEquals(
                "https://www.privacy.go.kr/cmm/fms/FileDown.do?atchFileId=ATCH_000000000938709&fileSn=1",
                attachment.getFileUrl()
        );
    }

    @Test
    void failsWhenListStructureIsMissing() {
        PiaNoticeCollector collector = new PiaNoticeCollector(uri -> "<html><body>점검 안내</body></html>");

        ExternalNoticeCollectionException exception = assertThrows(
                ExternalNoticeCollectionException.class,
                collector::collect
        );

        assertTrue(exception.getMessage().contains("목록 영역"));
    }

    @Test
    void failsWhenDetailNoticeIdDoesNotMatchRequestedNotice() {
        PiaNoticeCollector collector = new PiaNoticeCollector(uri -> {
            if (uri.toString().contains("bbsList.do")) {
                return readFixture("notice-list.html");
            }
            return """
                    <html><body>
                    <form id="viewForm"><input id="bbscttNo" value="99999"></form>
                    <div class="contentBox master_view"><div class="oneView"></div></div>
                    </body></html>
                    """;
        });

        ExternalNoticeCollectionException exception = assertThrows(
                ExternalNoticeCollectionException.class,
                collector::collect
        );

        assertTrue(exception.getMessage().contains("요청값과 다릅니다"));
    }

    private String loadFixtureForUri(URI uri) {
        String url = uri.toString();
        if (url.contains("bbsList.do")) {
            return readFixture("notice-list.html");
        }
        if (url.endsWith("bbscttNo=20878")) {
            return readFixture("notice-detail-without-attachment.html");
        }
        if (url.endsWith("bbscttNo=20907")) {
            return readFixture("notice-detail-with-attachment.html");
        }
        throw new IllegalArgumentException("정의되지 않은 테스트 URL: " + url);
    }

    private String readFixture(String fileName) {
        String resourcePath = "/externalnotice/pia/" + fileName;
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("테스트 fixture를 찾을 수 없습니다: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 fixture를 읽을 수 없습니다: " + resourcePath, e);
        }
    }
}
