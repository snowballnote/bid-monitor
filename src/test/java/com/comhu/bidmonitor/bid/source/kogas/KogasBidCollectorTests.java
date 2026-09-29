package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KogasBidCollectorTests {

    private static final String BASE_URL = "https://bid.kogas.or.kr:9443";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Test
    void parsesListDetailKoreanCharsetIdentityAndPublicAttachments() {
        FixtureTransport transport = new FixtureTransport(false);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(1, result.size());
        BidQualificationDto candidate = result.getFirst();
        assertEquals("KOGAS", candidate.getSourceCode());
        assertEquals("NC001:BC777", candidate.getSourceNoticeId());
        assertEquals("2", candidate.getRevision());
        assertEquals("천연가스 정보시스템 감리용역", candidate.getBidNtceNm());
        assertEquals("디지털혁신처", candidate.getNtceInsttNm());
        assertEquals("2026-09-10 09:00", candidate.getBidNtceDt());
        assertEquals("2026-09-30 17:00", candidate.getBidClseDt());
        assertEquals("공고중", candidate.getNoticeStatus());
        assertEquals("제한경쟁", candidate.getContractMethod());
        assertEquals("공고문 참조", candidate.getLicenseLimit());
        assertEquals("협상에 의한 계약", candidate.getSucsfbidMthdNm());
        assertEquals(
                BASE_URL + "/supplier/contents/bid/bid_detail_view_notice.jsp"
                        + "?notice_code=NC001&bid_code=BC777&round=2",
                candidate.getDetailUrl()
        );
        assertEquals(candidate.getDetailUrl(), candidate.getBidNtceDtlUrl());

        assertEquals(1, candidate.getAttachments().size());
        BidAttachmentDto attachment = candidate.getAttachments().getFirst();
        assertEquals(".._감리용역_공고문.hwp", attachment.getFileName());
        assertEquals(
                BASE_URL + "/supplier/bid/bid_download_attfile.jsp?notice_code=NC001&file_seq=1",
                attachment.getFileUrl()
        );
        assertEquals("공고문", attachment.getDocumentType());
        assertEquals("NOT_ANALYZED", attachment.getAnalysisStatus());

        assertEquals(1, transport.detailRequests("NC001"));
        assertEquals(1, transport.detailRequests("NC-BAD"));
        assertFalse(transport.requestedUris.stream().anyMatch(uri -> uri.toString().contains("NC002")));
    }

    @Test
    void distinguishesAnEmptyListFromAListRequestFailure() {
        KogasBidCollector emptyCollector = new KogasBidCollector(BASE_URL, new FixtureTransport(true));
        assertTrue(emptyCollector.collect(START, END).isEmpty());

        KogasBidCollector failingCollector = new KogasBidCollector(BASE_URL,
                uri -> new KogasBidCollector.Response(503, Map.of(), new byte[0]));
        assertThrows(IllegalStateException.class, () -> failingCollector.collect(START, END));
    }

    @Test
    void usesOnlyTheSourceCodeAndStaysDisabled() {
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, uri -> {
            throw new AssertionError("transport must not be called");
        });

        assertEquals("KOGAS", collector.sourceCode());
        assertFalse(collector.executionEnabled());
    }

    @Test
    void blocksDirectCollectionWhenExecutionEligibilityRejectsIt() {
        FixtureTransport transport = new FixtureTransport(false);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport, ignored -> false);

        assertThrows(IllegalStateException.class, () -> collector.collect(START, END));
        assertTrue(transport.requestedUris.isEmpty());
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/kogas", name), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static final class FixtureTransport implements KogasBidCollector.Transport {
        private final boolean empty;
        private final List<URI> requestedUris = new ArrayList<>();

        private FixtureTransport(boolean empty) {
            this.empty = empty;
        }

        @Override
        public KogasBidCollector.Response get(URI uri) {
            requestedUris.add(uri);
            if (uri.getPath().equals(KogasBidCollector.LIST_PATH)) {
                String html = fixture(empty ? "empty-list.html" : "list.html");
                return response(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8", 200);
            }
            if (uri.toString().contains("notice_code=NC-BAD")) {
                return response(new byte[0], "text/html; charset=EUC-KR", 500);
            }
            String html = fixture("detail.html");
            return response(html.getBytes(Charset.forName("EUC-KR")), "text/html; charset=EUC-KR", 200);
        }

        private int detailRequests(String noticeCode) {
            return (int) requestedUris.stream()
                    .filter(uri -> uri.getPath().equals(KogasBidCollector.DETAIL_PATH))
                    .filter(uri -> uri.toString().contains("notice_code=" + noticeCode))
                    .count();
        }

        private KogasBidCollector.Response response(byte[] body, String contentType, int status) {
            return new KogasBidCollector.Response(status, Map.of("Content-Type", List.of(contentType)), body);
        }
    }
}
