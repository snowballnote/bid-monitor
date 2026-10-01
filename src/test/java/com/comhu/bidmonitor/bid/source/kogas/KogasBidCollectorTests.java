package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.bid.source.registration.DiscoveredPublicPageAdapterFixtureSupport;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.service.BidQualificationEvaluationService;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertEquals("정보시스템 감리법인(6146), 소프트웨어사업자(1468)", candidate.getLicenseLimit());
        assertEquals(List.of("6146", "1468"), candidate.getLicenseGroups().getFirst().getRequirements().stream()
                .map(requirement -> requirement.getLicenseCode()).toList());
        assertEquals("제한없음", candidate.getParticipationRegion());
        assertEquals("협상에 의한 계약", candidate.getSucsfbidMthdNm());
        assertEquals("Y", candidate.getArsltCmptYn());
        assertEquals("Y", candidate.getPqEvalYn());
        assertEquals("Y", candidate.getTpEvalYn());
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

        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(candidate, Set.of("6146", "1468"));
        assertEquals("OTHER", candidate.getAwardMethodCategory());
        assertEquals("제외", candidate.getReviewStatus());
        assertEquals("UNKNOWN", candidate.getExternalCheckStatus());
    }

    @Test
    void comparesDedicatedAndGenericAdapterResultsFromTheSameKogasFixture() throws Exception {
        List<BidQualificationDto> dedicated = new KogasBidCollector(
                BASE_URL, new FixtureTransport(false)
        ).collect(START, END);
        List<BidQualificationDto> generic =
                DiscoveredPublicPageAdapterFixtureSupport.collectKogasFixture();

        assertEquals(1, dedicated.size());
        assertEquals(3, generic.size());
        BidQualificationDto dedicatedFirst = dedicated.getFirst();
        BidQualificationDto genericFirst = generic.getFirst();
        assertEquals("NC001:BC777", dedicatedFirst.getSourceNoticeId());
        assertTrue(genericFirst.getSourceNoticeId().contains("notice_code=NC001"));
        assertTrue(genericFirst.getSourceNoticeId().contains("bid_code=BC777"));
        assertEquals(dedicatedFirst.getBidNtceNm(), genericFirst.getBidNtceNm());
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

    @Test
    void requiresBothKogasConfigurationAndRegistrationActivationForEligibility() {
        BidSourceRegistrationRepository repository = mock(BidSourceRegistrationRepository.class);
        when(repository.findBySourceCode("KOGAS")).thenReturn(Optional.of(
                BidSourceRegistration.builder()
                        .sourceCode("KOGAS")
                        .registrationStatus(BidSourceRegistration.RegistrationStatus.APPROVED)
                        .collectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                        .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                        .executionEnabled(true)
                        .build()
        ));
        BidSourceExecutionEligibilityService eligibility =
                new BidSourceExecutionEligibilityService(repository);
        KogasBidCollector.Transport transport = uri -> {
            throw new AssertionError("Eligibility checks must not access KOGAS.");
        };

        KogasBidCollector disabled = new KogasBidCollector(
                BASE_URL, transport, eligibility::isEligible, false
        );
        KogasBidCollector enabled = new KogasBidCollector(
                BASE_URL, transport, eligibility::isEligible, true
        );

        assertFalse(disabled.executionEnabled());
        assertFalse(eligibility.isEligible(disabled));
        assertTrue(enabled.executionEnabled());
        assertTrue(eligibility.isEligible(enabled));
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
