package com.comhu.bidmonitor.bid.source.kogas;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.persistence.service.BidNoticeContentHasher;
import com.comhu.bidmonitor.bid.persistence.service.BidQualificationNoticeMapper;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.bid.source.registration.DiscoveredPublicPageAdapterFixtureSupport;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.service.BidQualificationEvaluationService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void parsesKogasListWithoutThHeadersUsingJavascriptDetailIdentity() {
        FixtureTransport transport = new FixtureTransport("list-td-headers.html", "detail-unknown.html");
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        String listHtml = fixture("list-td-headers.html");
        List<KogasBidCollector.ListNotice> notices = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        listHtml.getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        );

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(2, notices.size());
        assertEquals("NC-LIVE:BC-LIVE", notices.getFirst().key().sourceNoticeId());
        assertEquals("개인정보 영향평가", notices.getFirst().title());
        assertEquals(LocalDate.of(2026, 9, 15), notices.getFirst().publishedDate());
        assertEquals(1, result.size());
        assertEquals("KOGAS", result.getFirst().getSourceCode());
        assertEquals("NC-LIVE:BC-LIVE", result.getFirst().getSourceNoticeId());
        assertEquals("3", result.getFirst().getRevision());
        assertEquals("2026-09-15 00:00", result.getFirst().getBidNtceDt());
        assertTrue(result.getFirst().getLicenseGroups().isEmpty());
        assertEquals(1, transport.detailRequests("NC-LIVE"));
        assertEquals(0, transport.detailRequests("NC-SKIP"));
        assertTrue(transport.requestedUris.stream()
                .filter(uri -> uri.getPath().equals(KogasBidCollector.DETAIL_PATH))
                .anyMatch(uri -> "notice_code=NC-LIVE&bid_code=BC-LIVE&round=3".equals(uri.getRawQuery())));

        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(result.getFirst(), Set.of("6146", "1468"));
        assertEquals("UNKNOWN", result.getFirst().getAwardMethodCategory());
        assertEquals("추가확인필요", result.getFirst().getReviewStatus());
        assertEquals("UNKNOWN", result.getFirst().getExternalCheckStatus());
    }

    @Test
    void parsesActualEightColumnTdHeaderStructureWithoutDependingOnTablePositionOrClassAlone() {
        FixtureTransport transport = new FixtureTransport("list-actual-dom.html", "detail-unknown.html");
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);
        String listHtml = fixture("list-actual-dom.html");

        List<KogasBidCollector.ListNotice> notices = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        listHtml.getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        );

        assertEquals(3, notices.size());
        KogasBidCollector.ListNotice first = notices.getFirst();
        assertEquals("2026100603001", first.noticeCode());
        assertEquals("BID-A", first.bidCode());
        assertEquals("합숙소 비품 운반", first.title());
        assertEquals("가격조사", first.bidForm());
        assertEquals("용역", first.category());
        assertEquals("일반경쟁", first.contractMethod());
        assertEquals("2026.10.12 10:00", first.deadline());
        assertEquals("2026.10.12 11:00", first.openingDate());
        assertEquals("N", first.status());
        assertEquals(
                BASE_URL + KogasBidCollector.DETAIL_PATH,
                first.detailUri().toASCIIString()
        );
        assertEquals(
                Map.of("notice_code", "2026100603001", "bid_code", "BID-A", "round", "1"),
                first.detailFormFields()
        );
        assertEquals(KogasBidCollector.BID_SALE_DETAIL_PATH, notices.get(1).detailUri().getPath());
        assertEquals(KogasBidCollector.HD_DETAIL_PATH, notices.get(2).detailUri().getPath());

        BidQualificationDto mapped = collector.toQualification(
                first, new KogasBidCollector.DetailData(Map.of(), List.of())
        );
        assertEquals("가격조사", mapped.getBidForm());
        assertEquals("일반경쟁", mapped.getContractMethod());
        assertEquals("2026-10-12 10:00", mapped.getBidClseDt());
        assertEquals("2026-10-12 11:00", mapped.getBidOpeningDt());

        List<BidQualificationDto> collected = collector.collect(START, END);
        assertEquals(2, collected.size());
        assertEquals(1, transport.detailRequests("2026100603001"));
        assertEquals(0, transport.detailRequests("2026100603002"));
        assertEquals(1, transport.detailRequests("2026100603003"));
        assertEquals(0, transport.detailRequests("MALFORMED"));
        assertEquals(KogasBidCollector.DETAIL_PATH, transport.postRequests.getFirst().uri().getPath());
        assertEquals(KogasBidCollector.HD_DETAIL_PATH, transport.postRequests.get(1).uri().getPath());
        assertEquals(Set.of("notice_code", "bid_code", "round"),
                transport.postRequests.getFirst().formFields().keySet());
        assertFalse(transport.postRequests.getFirst().formFields().containsKey("type"));
    }

    @Test
    void filtersByDetailPublishedDateWhenListPublishedDateIsMissing() {
        FixtureTransport transport = new FixtureTransport("list-actual-dom.html", "detail-actual-dom.html");
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);
        List<KogasBidCollector.ListNotice> notices = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("list-actual-dom.html").getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        );

        assertNull(notices.getFirst().publishedDate());
        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)
        );

        assertEquals(2, collected.size());
        assertTrue(collected.stream().allMatch(candidate -> "2026-10-01 09:54".equals(candidate.getBidNtceDt())));
        assertEquals(1, transport.detailRequests("2026100603001"));
        assertEquals(1, transport.detailRequests("2026100603003"));
    }

    @Test
    void excludesCandidateWhenDetailPublishedDateIsOutsideRequestedPeriod() {
        FixtureTransport transport = new FixtureTransport("list-actual-dom.html", "detail-actual-dom.html");
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 7)
        );

        assertTrue(collected.isEmpty());
        assertEquals(1, transport.detailRequests("2026100603001"));
        assertEquals(1, transport.detailRequests("2026100603003"));
    }

    @Test
    void keepsCandidateWhenDetailPublishedDateIsStillMissing() {
        KogasBidCollector collector = new KogasBidCollector(
                BASE_URL, new FixtureTransport("list-actual-dom.html", "detail-unknown.html")
        );

        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 7)
        );

        assertEquals(2, collected.size());
        assertTrue(collected.stream().allMatch(candidate -> candidate.getBidNtceDt() == null));
    }

    @Test
    void includesDetailPublishedDateOnBothRequestedPeriodBoundaries() {
        KogasBidCollector collector = new KogasBidCollector(
                BASE_URL, new FixtureTransport("list-actual-dom.html", "detail-actual-dom.html")
        );

        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)
        );

        assertEquals(2, collected.size());
    }

    @Test
    void parsesActualTdQualificationFieldsAndPassesThemToCommonEvaluation() {
        KogasBidCollector collector = new KogasBidCollector(
                BASE_URL, new FixtureTransport("list-actual-dom.html", "detail-actual-dom.html")
        );
        KogasBidCollector.DetailData detail = collector.parseDetail(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("detail-actual-dom.html").getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.DETAIL_PATH)
        );
        KogasBidCollector.ListNotice notice = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("list-actual-dom.html").getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        ).getFirst();

        assertEquals("제한경쟁 -적격심사 -총액", detail.fields().get("계약방법"));
        assertEquals("정보시스템 감리법인 업종을 등록한 업체", detail.fields().get("업종그룹1"));
        assertEquals("소프트웨어사업자(컴퓨터관련서비스사업) 업종을 등록한 업체",
                detail.fields().get("업종그룹2"));
        assertEquals("전국대상 [전국])", detail.fields().get("지역제한"));

        BidQualificationDto qualification = collector.toQualification(notice, detail);
        assertEquals("합숙소 비품 운반", qualification.getBidNtceNm());
        assertEquals("제한경쟁 -적격심사 -총액", qualification.getContractMethod());
        assertEquals("예정가격이하 최저가 입찰자를 대상으로 적격여부를 심사하여 낙찰자를 결정",
                qualification.getSucsfbidMthdNm());
        assertEquals(List.of("6146"), qualification.getLicenseGroups().get(0).getRequirements().stream()
                .map(requirement -> requirement.getLicenseCode()).toList());
        assertEquals(List.of("1468"), qualification.getLicenseGroups().get(1).getRequirements().stream()
                .map(requirement -> requirement.getLicenseCode()).toList());
        assertEquals("제한없음", qualification.getParticipationRegion());
        assertEquals("N", qualification.getPqEvalYn());
        assertEquals("단일도급", qualification.getCmmnSpldmdAgrmntRcptdocMethd());
        assertEquals(1, qualification.getAttachments().size());

        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(qualification, Set.of("6146", "1468"));
        assertEquals("QUALIFICATION_REVIEW", qualification.getAwardMethodCategory());
        assertEquals("검토대상", qualification.getReviewStatus());

        Map<String, String> restrictedFields = new java.util.LinkedHashMap<>(detail.fields());
        restrictedFields.put("지역제한", "대구광역시");
        BidQualificationDto restricted = collector.toQualification(
                notice, new KogasBidCollector.DetailData(restrictedFields, detail.attachments())
        );
        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(restricted, Set.of("6146", "1468"));
        assertEquals("대구광역시", restricted.getParticipationRegion());
        assertEquals("추가확인필요", restricted.getReviewStatus());
        assertTrue(restricted.getReviewReason().contains("지역제한 조건 확인 필요: 대구광역시"));
    }

    @Test
    void normalizesOnlyConfirmedKogasLicenseExpressionsAndPreservesUnknownIndustryText() {
        KogasBidCollector collector = new KogasBidCollector(
                BASE_URL, new FixtureTransport("list-actual-dom.html", "detail-unknown.html")
        );
        KogasBidCollector.ListNotice notice = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("list-actual-dom.html").getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        ).getFirst();

        BidQualificationDto supervision = collector.toQualification(
                notice,
                new KogasBidCollector.DetailData(
                        Map.of("업종그룹1", "정보시스템 감리법인 업종을 등록한 업체"), List.of()
                )
        );
        assertEquals(List.of("6146"), licenseCodes(supervision));

        BidQualificationDto computerService = collector.toQualification(
                notice,
                new KogasBidCollector.DetailData(
                        Map.of("업종그룹1", "소프트웨어사업자(컴퓨터관련서비스사업) 업종을 등록한 업체"),
                        List.of()
                )
        );
        assertEquals(List.of("1468"), licenseCodes(computerService));

        BidQualificationDto bothCodes = collector.toQualification(
                notice,
                new KogasBidCollector.DetailData(
                        Map.of("입찰참가자격", "업종코드 6146 또는 1468로 등록한 업체"), List.of()
                )
        );
        assertEquals(List.of("6146", "1468"), licenseCodes(bothCodes));

        BidQualificationDto unrelatedIndustry = collector.toQualification(
                notice,
                new KogasBidCollector.DetailData(
                        Map.of("업종그룹1", "정보통신공사업 업종을 등록한 업체"), List.of()
                )
        );
        assertEquals(List.of(""), licenseCodes(unrelatedIndustry));
        assertEquals("정보통신공사업 업종을 등록한 업체",
                unrelatedIndustry.getLicenseGroups().getFirst().getRequirements().getFirst().getLicenseName());

        BidQualificationDto noLicenseInformation = collector.toQualification(
                notice, new KogasBidCollector.DetailData(Map.of(), List.of())
        );
        assertTrue(noLicenseInformation.getLicenseGroups().isEmpty());

        BidQualificationDto emptyPublishedGroups = collector.toQualification(
                notice,
                new KogasBidCollector.DetailData(
                        Map.of("면허사항제한", "업종그룹1 - 업종그룹2 -", "업종그룹1", "-", "업종그룹2", "-"),
                        List.of()
                )
        );
        assertTrue(emptyPublishedGroups.getLicenseGroups().isEmpty());
    }

    @Test
    void mapsActualNonDateOpeningValueThroughPersistenceMapper() {
        KogasBidCollector collector = new KogasBidCollector(
                BASE_URL, new FixtureTransport("list-actual-dom.html", "detail-non-date-opening.html")
        );
        URI detailUri = URI.create(BASE_URL + KogasBidCollector.DETAIL_PATH);
        KogasBidCollector.DetailData detail = collector.parseDetail(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("detail-non-date-opening.html").getBytes(StandardCharsets.UTF_8)
                ),
                detailUri
        );
        KogasBidCollector.ListNotice notice = collector.parseList(
                new KogasBidCollector.Response(
                        200,
                        Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                        fixture("list-actual-dom.html").getBytes(StandardCharsets.UTF_8)
                ),
                URI.create(BASE_URL + KogasBidCollector.LIST_PATH)
        ).getFirst();

        BidQualificationDto qualification = collector.toQualification(notice, detail);
        assertEquals("KOGAS", qualification.getSourceCode());
        assertEquals("2026100603001:BID-A", qualification.getSourceNoticeId());
        assertEquals("2015-10-01 14:52", qualification.getBidNtceDt());
        assertEquals("2015-10-16 11:00", qualification.getBidClseDt());
        assertNull(qualification.getBidOpeningDt());

        BidNotice mapped = new BidQualificationNoticeMapper(new BidNoticeContentHasher())
                .map(qualification, Instant.parse("2026-10-07T00:00:00Z"));
        assertEquals("KOGAS", mapped.getSourceCode());
        assertEquals("2026100603001:BID-A", mapped.getSourceNoticeId());
        assertNull(mapped.getBidOpeningAt());
    }

    @Test
    void paginatesThroughReportedLastPageAndDeduplicatesNoticesAcrossPages() {
        PagingFixtureTransport transport = new PagingFixtureTransport(false, 2);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)
        );

        assertEquals(List.of(1, 2), transport.requestedPages);
        assertEquals(3, collected.size());
        assertEquals(1, transport.detailRequests("2026100603001"));
        assertEquals(1, transport.detailRequests("2026100603003"));
        assertEquals(1, transport.detailRequests("2026100604003"));
        URI secondPage = transport.listRequests.get(1);
        assertEquals("page=2&worktype=&title=&e_startday=&e_endday=&o_startday=&o_endday=&orderplace=&reqbidno=",
                secondPage.getRawQuery());
    }

    @Test
    void stopsWhenPaginationRepeatsInsteadOfLooping() {
        PagingFixtureTransport transport = new PagingFixtureTransport(true, 3);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        assertThrows(IllegalStateException.class, () -> collector.collect(START, END));
        assertEquals(List.of(1, 2), transport.requestedPages);
        assertTrue(transport.postRequests.isEmpty());
    }

    @Test
    void rejectsPaginationAboveTheSafeBound() {
        PagingFixtureTransport transport = new PagingFixtureTransport(false, 201);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        assertThrows(IllegalStateException.class, () -> collector.collect(START, END));
        assertEquals(List.of(1), transport.requestedPages);
        assertTrue(transport.postRequests.isEmpty());
    }

    @Test
    void keepsTheExistingPublishedDateRangeMeaning() {
        FixtureTransport transport = new FixtureTransport(false);
        KogasBidCollector collector = new KogasBidCollector(BASE_URL, transport);

        List<BidQualificationDto> collected = collector.collect(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)
        );

        assertTrue(collected.isEmpty());
        assertTrue(transport.postRequests.isEmpty());
        assertEquals(1, transport.requestedUris.size());
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
    void logsOnlyBoundedListStructureMetadataAtDebugLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(KogasBidCollector.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            String longCellText = "A".repeat(100) + "TAIL_SECRET";
            StringBuilder html = new StringBuilder("""
                    <html>
                      <head>
                        <title>KOGAS diagnostic page</title>
                        <script>
                          function viewBid(noticeKey, bidKey, bidRound, type) {
                            document.bidForm.notice_code.value = noticeKey;
                            document.bidForm.bid_code.value = bidKey;
                            document.bidForm.round.value = bidRound;
                            document.bidForm.method = 'post';
                            document.bidForm.action = '/supplier/contents/bid/bid_detail_view_notice.jsp';
                            if (type === 'BID_SALE') {
                              document.bidForm.action = '/supplier/contents/bid/bid_detail_view_bidsale.jsp';
                            } else if ('HD' == type) {
                              document.bidForm.action = '/supplier/contents/bid/bid_detail_view_hd.jsp';
                            }
                            document.bidForm.submit();
                          }
                        </script>
                      </head>
                      <body id="publicBody" class="bid list">
                        <form id="searchForm" name="bidForm" class="search" method="get"
                              action="/supplier/fallback.jsp?token=ACTION_SECRET">
                          <input type="hidden" name="notice_code" value="NOTICE_VALUE_SECRET">
                          <input type="hidden" name="bid_code" value="BID_VALUE_SECRET">
                          <input type="hidden" name="round" value="ROUND_VALUE_SECRET">
                          <table id="resultTable" class="list-table">
                            <tr><th>Type</th><th>Title</th></tr>
                    """);
            html.append("<tr><td class='long-cell'>").append(longCellText).append("</td>")
                    .append("<td class='action-cell'>")
                    .append("<a id='detailLink' class='detail-link' href='/detail?token=URL_SECRET' ")
                    .append("onclick=\"openBid('NOTICE,SECRET', 3, 'TOKEN_SECRET')\">Open detail</a>")
                    .append("<button id='detailButton' class='detail-button' ")
                    .append("onclick=\"return submitBid('BUTTON_SECRET')\">Open button</button>")
                    .append("<input type='hidden' id='hiddenNotice' class='hidden-input' value='HIDDEN_SECRET'>")
                    .append("</td></tr>")
                    .append("<tr><td>row-2</td></tr>")
                    .append("<tr><td>row-3</td></tr>")
                    .append("<tr>");
            for (int cellIndex = 0; cellIndex < 13; cellIndex++) {
                html.append("<td class='cell-").append(cellIndex).append("'>cell-")
                        .append(cellIndex).append("</td>");
            }
            html.append("</tr><tr><td>ROW_LIMIT_SECRET</td></tr></table></form>");
            for (int tableIndex = 1; tableIndex <= 10; tableIndex++) {
                html.append("<table id='table-").append(tableIndex)
                        .append("'><tr><td>table-").append(tableIndex).append("</td></tr></table>");
            }
            html.append("</body></html>");
            URI requestedUri = URI.create(BASE_URL + KogasBidCollector.LIST_PATH);
            URI responseUri = URI.create(BASE_URL + "/supplier/final-list.jsp?session=private-session");
            KogasBidCollector.Response response = new KogasBidCollector.Response(
                    200,
                    Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                    html.toString().getBytes(StandardCharsets.UTF_8),
                    responseUri
            );
            KogasBidCollector collector = new KogasBidCollector(BASE_URL, uri -> response);

            assertThrows(IllegalStateException.class, () -> collector.parseList(response, requestedUri));

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(output.contains("status=200"));
            assertTrue(output.contains("contentType=text/html; charset=UTF-8"));
            assertTrue(output.contains("responseHost=bid.kogas.or.kr"));
            assertTrue(output.contains("responsePath=/supplier/final-list.jsp"));
            assertTrue(output.contains("title=KOGAS diagnostic page"));
            assertTrue(output.contains("tableCount=11"));
            assertTrue(output.contains("headers=[Type, Title]"));
            assertTrue(output.contains("formId=searchForm"));
            assertTrue(output.contains("tableIndex=9"));
            assertFalse(output.contains("tableIndex=10"));
            assertFalse(output.contains("KOGAS viewBid function structure"));
            assertFalse(output.contains("KOGAS list row structure"));
            assertFalse(output.contains("KOGAS list cell structure"));
            assertFalse(output.contains("TAIL_SECRET"));
            assertFalse(output.contains("ROW_LIMIT_SECRET"));
            assertFalse(output.contains("/detail"));
            assertFalse(output.contains("URL_SECRET"));
            assertFalse(output.contains("NOTICE,SECRET"));
            assertFalse(output.contains("TOKEN_SECRET"));
            assertFalse(output.contains("BUTTON_SECRET"));
            assertFalse(output.contains("HIDDEN_SECRET"));
            assertFalse(output.contains("ACTION_SECRET"));
            assertFalse(output.contains("NOTICE_VALUE_SECRET"));
            assertFalse(output.contains("BID_VALUE_SECRET"));
            assertFalse(output.contains("ROUND_VALUE_SECRET"));
            assertFalse(output.contains("private-session"));
            assertFalse(output.contains("token=hidden"));
            assertTrue(appender.list.stream().allMatch(event -> event.getLevel() == Level.DEBUG));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
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

    private static List<String> licenseCodes(BidQualificationDto qualification) {
        return qualification.getLicenseGroups().stream()
                .flatMap(group -> group.getRequirements().stream())
                .map(requirement -> requirement.getLicenseCode())
                .toList();
    }

    private static final class FixtureTransport implements KogasBidCollector.Transport {
        private final String listFixture;
        private final String detailFixture;
        private final List<URI> requestedUris = new ArrayList<>();
        private final List<PostRequest> postRequests = new ArrayList<>();

        private FixtureTransport(boolean empty) {
            this(empty ? "empty-list.html" : "list.html");
        }

        private FixtureTransport(String listFixture) {
            this(listFixture, "detail.html");
        }

        private FixtureTransport(String listFixture, String detailFixture) {
            this.listFixture = listFixture;
            this.detailFixture = detailFixture;
        }

        @Override
        public KogasBidCollector.Response get(URI uri) {
            requestedUris.add(uri);
            if (uri.getPath().equals(KogasBidCollector.LIST_PATH)) {
                String html = fixture(listFixture);
                return response(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8", 200);
            }
            if (uri.toString().contains("notice_code=NC-BAD")) {
                return response(new byte[0], "text/html; charset=EUC-KR", 500);
            }
            String html = fixture(detailFixture);
            return response(html.getBytes(Charset.forName("EUC-KR")), "text/html; charset=EUC-KR", 200);
        }

        @Override
        public KogasBidCollector.Response post(URI uri, Map<String, String> formFields) {
            requestedUris.add(uri);
            postRequests.add(new PostRequest(uri, Map.copyOf(formFields)));
            String html = fixture(detailFixture);
            return response(html.getBytes(Charset.forName("EUC-KR")), "text/html; charset=EUC-KR", 200);
        }

        private int detailRequests(String noticeCode) {
            long getRequests = requestedUris.stream()
                    .filter(uri -> uri.getPath().equals(KogasBidCollector.DETAIL_PATH))
                    .filter(uri -> uri.toString().contains("notice_code=" + noticeCode))
                    .count();
            long postRequestCount = postRequests.stream()
                    .filter(request -> noticeCode.equals(request.formFields().get("notice_code")))
                    .count();
            return Math.toIntExact(getRequests + postRequestCount);
        }

        private KogasBidCollector.Response response(byte[] body, String contentType, int status) {
            return new KogasBidCollector.Response(status, Map.of("Content-Type", List.of(contentType)), body);
        }

        private record PostRequest(URI uri, Map<String, String> formFields) {
        }
    }

    private static final class PagingFixtureTransport implements KogasBidCollector.Transport {
        private final boolean repeatPage;
        private final int totalPages;
        private final List<Integer> requestedPages = new ArrayList<>();
        private final List<URI> listRequests = new ArrayList<>();
        private final List<FixtureTransport.PostRequest> postRequests = new ArrayList<>();

        private PagingFixtureTransport(boolean repeatPage, int totalPages) {
            this.repeatPage = repeatPage;
            this.totalPages = totalPages;
        }

        @Override
        public KogasBidCollector.Response get(URI uri) {
            if (!uri.getPath().equals(KogasBidCollector.LIST_PATH)) {
                throw new AssertionError("Unexpected GET: " + uri.getPath());
            }
            int page = pageNumber(uri);
            requestedPages.add(page);
            listRequests.add(uri);
            String html = fixture("list-actual-dom.html");
            if (page > 1 && !repeatPage) {
                html = html.replace("2026100603002", "2026100604002")
                        .replace("BID-B", "BID-D")
                        .replace("2026100603003", "2026100604003")
                        .replace("BID-C", "BID-E");
            }
            html = html.replace("</body>", "<div>Total Records : 5 Pages : "
                    + page + "/" + totalPages + "</div></body>");
            return response(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8", 200);
        }

        @Override
        public KogasBidCollector.Response post(URI uri, Map<String, String> formFields) {
            postRequests.add(new FixtureTransport.PostRequest(uri, Map.copyOf(formFields)));
            String html = fixture("detail-unknown.html");
            return response(html.getBytes(Charset.forName("EUC-KR")), "text/html; charset=EUC-KR", 200);
        }

        private int detailRequests(String noticeCode) {
            return Math.toIntExact(postRequests.stream()
                    .filter(request -> noticeCode.equals(request.formFields().get("notice_code")))
                    .count());
        }

        private int pageNumber(URI uri) {
            if (uri.getRawQuery() == null) {
                return 1;
            }
            for (String parameter : uri.getRawQuery().split("&", -1)) {
                if (parameter.startsWith("page=")) {
                    return Integer.parseInt(parameter.substring("page=".length()));
                }
            }
            throw new AssertionError("Missing page parameter");
        }

        private KogasBidCollector.Response response(byte[] body, String contentType, int status) {
            return new KogasBidCollector.Response(status, Map.of("Content-Type", List.of(contentType)), body);
        }
    }
}
