package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// 나라장터(G2B) OpenAPI 호출을 담당하는 서비스 클래스
@Service
public class G2bApiService {

    private static final int BID_LIST_PAGE_SIZE = 100;
    private static final String REQUIRED_LICENSE_CODE = "6146";
    private static final Set<String> DEFAULT_ALLOWED_LICENSE_CODES = Set.of("6146", "1468");
    private static final Set<String> REFERENCE_SITE_DOMAINS = Set.of("smpp.go.kr");
    private static final Set<String> PDF_ANALYSIS_DOCUMENT_TYPES =
            Set.of("공고문", "과업지시서", "제안요청서");
    private static final Set<String> HWPX_ANALYSIS_DOCUMENT_TYPES =
            Set.of("공고문", "과업지시서", "제안요청서");
    private static final Set<String> HWP_ANALYSIS_DOCUMENT_TYPES =
            Set.of("공고문", "과업지시서", "제안요청서");
    private static final int MAX_HWPX_SECTION_XML_BYTES = 25 * 1024 * 1024;
    private static final int MAX_HWP_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    private static final int MAX_HWP_EXTRACTED_TEXT_LENGTH = 2_000_000;
    private static final int HWP_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int HWP_READ_TIMEOUT_MILLIS = 30_000;
    private static final int HWP_ANALYSIS_TIMEOUT_SECONDS = 30;
    private static final byte[] OLE_COMPOUND_FILE_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final byte[] HWP_DOCUMENT_FILE_SIGNATURE =
            "HWP Document File".getBytes(StandardCharsets.US_ASCII);
    private static final ThreadPoolExecutor HWP_ANALYSIS_EXECUTOR = new ThreadPoolExecutor(
            1,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            runnable -> {
                Thread thread = new Thread(runnable, "hwp-attachment-analysis");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private static final Pattern HWPX_SECTION_XML_PATTERN =
            Pattern.compile("(?i)^Contents/section\\d+\\.xml$");
    private static final List<String> EXTERNAL_REFERENCE_KEYWORDS = List.of(
            "기관 홈페이지 참조",
            "홈페이지 참조",
            "자세한 내용은 홈페이지",
            "별도 사이트",
            "직접 제출",
            "외부 사이트"
    );
    private static final Pattern HTTP_URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s<>\\[\\]{}\\\"']+");
    private static final Pattern TOP_LEVEL_SECTION_HEADING_PATTERN =
            Pattern.compile("^\\s*(?:(?:\\d+\\s*[.)])|(?:[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+(?:\\.|\\s)))\\s*.+");
    private static final Pattern DEADLINE_VALUE_PATTERN = Pattern.compile(
            ".*(?:[‘’']?\\d{2,4}[.-]\\s*\\d{1,2}(?:[.-]\\s*\\d{1,2})?|\\d{1,2}:\\d{2}|까지|도착분).*"
    );
    private static final List<String> REQUIRED_DOCUMENT_HEADINGS =
            List.of("제출서류", "제출 서류", "구비서류", "구비 서류");
    private static final List<String> QUALIFICATION_HEADINGS =
            List.of("입찰 참가자격", "입찰참가자격", "참가자격");
    private static final List<String> SUBMISSION_METHOD_KEYWORDS = List.of(
            "제출방법", "제출 방법", "제출처", "제출 및 문의처",
            "우편", "직접 제출", "방문 제출", "전자 제출", "전자제출",
            "온라인 제출", "이메일", "E-mail", "e-mail"
    );
    private static final List<String> SUBMISSION_DEADLINE_KEYWORDS = List.of(
            "제출기한", "제출 기한", "접수기한", "접수 기한",
            "제출마감", "제출 마감", "접수마감", "접수 마감", "마감일"
    );
    private static final List<String> JOINT_CONTRACT_KEYWORDS =
            List.of("공동수급협정서", "공동수급", "공동도급");

    // application.properties에 설정한 나라장터 API 기본 주소를 가져옴
    @Value("${g2b.api.base-url}")
    private String baseUrl;

    // Windows 환경변수에 저장된 나라장터 API 인증키를 가져옴
    // 실제 인증키가 GitHub에 노출되지 않도록 소스코드에는 직접 작성하지 않음
    @Value("${g2b.api.service-key}")
    private String serviceKey;

    /**
     * 나라장터 용역 입찰공고 목록을 테스트용으로 조회한다.
     */
    public String getBidList() {
        // 실행 당일의 입찰공고를 조회하기 위해 현재 날짜를 yyyyMMdd 형식으로 만든다.
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //String today = "20260713"; // 테스트
        String inquiryStartDateTime = today + "0000";
        String inquiryEndDateTime = today + "2359";

        // 브라우저에서 정상 호출된 URL과 동일한 형태로 전체 요청 주소를 직접 만든다.
        // ServiceKey가 이미 URL Encoding된 값이므로 RestClient가 다시 인코딩하지 않도록
        // 최종 문자열을 URI 객체로 변환해 그대로 전달한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServcPPSSrch"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=1"
                + "&inqryBgnDt=" + inquiryStartDateTime
                + "&inqryEndDt=" + inquiryEndDateTime
                // 업종코드 6146에 해당하는 용역 입찰공고만 조회한다.
                + "&indstrytyCd=6146";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 사용자가 지정한 기간의 용역 감리 입찰공고 목록을 조회한다.
     */
    public String getBidList(LocalDate startDate, LocalDate endDate) {
        String responseBody = requestBidListPage(startDate, endDate, 1);

        // 공개 메서드를 직접 호출한 경우에도 나라장터 오류 응답을 정상 결과로 오인하지 않는다.
        parseBidListPage(responseBody);
        return responseBody;
    }

    /**
     * 지정한 기간과 페이지 번호로 나라장터 용역 감리 공고를 조회한다.
     */
    private String requestBidListPage(LocalDate startDate, LocalDate endDate, int pageNo) {
        String inquiryStartDateTime = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "0000";
        String inquiryEndDateTime = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "2359";

        // 기존 목록 조회와 같은 오퍼레이션에서 구간과 페이지 번호만 변경해 호출한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServcPPSSrch"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=" + BID_LIST_PAGE_SIZE
                + "&pageNo=" + pageNo
                + "&type=json"
                + "&inqryDiv=1"
                + "&inqryBgnDt=" + inquiryStartDateTime
                + "&inqryEndDt=" + inquiryEndDateTime
                + "&indstrytyCd=6146";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 특정 입찰공고의 면허제한정보를 테스트 용도로 조회한다.
     */
    public String getLicenseLimit(String bidNtceNo) {
        // 면허제한정보는 해당 공고에 참여하기 위해 필요한 업종·면허 자격을 확인하는 데 사용한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoLicenseLimit"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 특정 입찰공고의 참가가능지역정보를 테스트 용도로 조회한다.
     */
    public String getParticipationRegion(String bidNtceNo) {
        // 참가가능지역정보는 입찰 참여가 허용되는 지역을 확인하여 지역 제한 여부를 판단하는 데 사용한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoPrtcptPsblRgn"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 분석 대상 PDF 첨부파일 한 건을 내려받아 외부 홈페이지 확인 신호를 탐지한다.
     * 공고 전체 판정과는 연결하지 않고 BidAttachmentDto의 분석 결과만 갱신한다.
     */
    public BidAttachmentDto analyzePdfAttachment(BidAttachmentDto attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException("분석할 첨부파일 정보가 없습니다.");
        }
        if (!isPdfAnalysisTarget(attachment)) {
            return attachment;
        }

        // 재분석할 때 이전 결과가 남지 않도록 분석 관련 필드를 먼저 초기화한다.
        attachment.setExternalReferenceDetected(false);
        attachment.setDetectedExternalUrls(new ArrayList<>());
        attachment.setAnalysisReason("");
        attachment.setDocumentAnalysis(new BidDocumentAnalysisDto());

        try {
            byte[] pdfBytes = RestClient.create()
                    .get()
                    .uri(URI.create(attachment.getFileUrl()))
                    .retrieve()
                    .body(byte[].class);
            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new IllegalStateException("다운로드한 PDF 파일이 비어 있습니다.");
            }

            String pdfText;
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                pdfText = new PDFTextStripper().getText(document);
            }
            attachment.setDocumentAnalysis(analyzeBidDocumentText(pdfText));

            List<String> detectedKeywords = findExternalReferenceKeywords(pdfText);
            List<String> detectedExternalUrls = findExternalUrls(pdfText);
            boolean externalReferenceDetected =
                    !detectedKeywords.isEmpty() || !detectedExternalUrls.isEmpty();

            attachment.setAnalysisStatus("ANALYZED");
            attachment.setExternalReferenceDetected(externalReferenceDetected);
            attachment.setDetectedExternalUrls(detectedExternalUrls);
            attachment.setAnalysisReason(createPdfAnalysisReason(
                    pdfText,
                    detectedKeywords,
                    detectedExternalUrls
            ));
        } catch (Exception e) {
            // 한 파일의 다운로드 또는 텍스트 추출 실패가 기존 공고 판정에 영향을 주지 않도록 상태만 기록한다.
            attachment.setAnalysisStatus("FAILED");
            attachment.setExternalReferenceDetected(false);
            attachment.setDetectedExternalUrls(new ArrayList<>());
            String failureMessage = getSafeValue(e.getMessage()).trim();
            attachment.setDocumentAnalysis(createFailedDocumentAnalysis("PDF 본문 분석 실패", e));
            attachment.setAnalysisReason("PDF 분석 실패"
                    + (failureMessage.isEmpty() ? "" : ": " + failureMessage));
        }

        return attachment;
    }

    /**
     * 분석 대상 HWPX 첨부파일 한 건의 ZIP 내부 본문 XML을 읽어 외부 홈페이지 확인 신호를 탐지한다.
     * 기존 공고 자동판정에는 연결하지 않고 BidAttachmentDto의 분석 결과만 갱신한다.
     */
    public BidAttachmentDto analyzeHwpxAttachment(BidAttachmentDto attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException("분석할 첨부파일 정보가 없습니다.");
        }
        if (!isHwpxAnalysisTarget(attachment)) {
            return attachment;
        }

        // 재분석 시 이전 파일의 탐지 결과가 남지 않도록 분석 필드를 초기화한다.
        attachment.setExternalReferenceDetected(false);
        attachment.setDetectedExternalUrls(new ArrayList<>());
        attachment.setAnalysisReason("");
        attachment.setDocumentAnalysis(new BidDocumentAnalysisDto());

        try {
            byte[] hwpxBytes = RestClient.create()
                    .get()
                    .uri(URI.create(attachment.getFileUrl()))
                    .retrieve()
                    .body(byte[].class);
            if (hwpxBytes == null || hwpxBytes.length == 0) {
                throw new IllegalStateException("다운로드한 HWPX 파일이 비어 있습니다.");
            }

            String hwpxText = extractHwpxText(hwpxBytes);
            if (hwpxText.isBlank()) {
                throw new IllegalStateException("HWPX 본문에서 텍스트를 추출하지 못했습니다.");
            }
            attachment.setDocumentAnalysis(analyzeBidDocumentText(hwpxText));

            // PDF 분석과 동일한 키워드 및 외부 URL 탐지 기준을 재사용한다.
            List<String> detectedKeywords = findExternalReferenceKeywords(hwpxText);
            List<String> detectedExternalUrls = findExternalUrls(hwpxText);
            boolean externalReferenceDetected =
                    !detectedKeywords.isEmpty() || !detectedExternalUrls.isEmpty();

            attachment.setAnalysisStatus("ANALYZED");
            attachment.setExternalReferenceDetected(externalReferenceDetected);
            attachment.setDetectedExternalUrls(detectedExternalUrls);
            attachment.setAnalysisReason(createHwpxAnalysisReason(
                    detectedKeywords,
                    detectedExternalUrls
            ));
        } catch (Exception e) {
            // 다운로드·압축 해제·XML 파싱 실패는 해당 첨부파일의 분석 상태에만 기록한다.
            attachment.setAnalysisStatus("FAILED");
            attachment.setExternalReferenceDetected(false);
            attachment.setDetectedExternalUrls(new ArrayList<>());
            String failureMessage = getSafeValue(e.getMessage()).trim();
            attachment.setDocumentAnalysis(createFailedDocumentAnalysis("HWPX 본문 분석 실패", e));
            attachment.setAnalysisReason("HWPX 분석 실패"
                    + (failureMessage.isEmpty() ? "" : ": " + failureMessage));
        }

        return attachment;
    }

    /**
     * 분석 대상 HWP 첨부파일 한 건의 HWP 5.x 본문을 읽어 외부 홈페이지 확인 신호를 탐지한다.
     * 파일 하나의 실패가 공고 전체 조회를 중단하지 않도록 실패 결과는 해당 첨부에만 기록한다.
     */
    public BidAttachmentDto analyzeHwpAttachment(BidAttachmentDto attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException("분석할 첨부파일 정보가 없습니다.");
        }
        if (!isHwpAnalysisTarget(attachment)) {
            return attachment;
        }

        attachment.setExternalReferenceDetected(false);
        attachment.setDetectedExternalUrls(new ArrayList<>());
        attachment.setAnalysisReason("");
        attachment.setDocumentAnalysis(new BidDocumentAnalysisDto());

        try {
            // 응답을 제한된 크기로 내려받고 HWP 5.x의 OLE 및 내부 파일 서명을 먼저 확인한다.
            byte[] hwpBytes = downloadHwpAttachment(attachment.getFileUrl());
            validateHwpSignature(hwpBytes);

            // 표와 컨트롤의 텍스트도 가능한 범위에서 본문 사이에 포함해 추출한다.
            String hwpText = extractHwpTextWithTimeout(hwpBytes);
            if (hwpText.isBlank()) {
                throw new IllegalStateException("HWP 본문에서 텍스트를 추출하지 못했습니다.");
            }
            attachment.setDocumentAnalysis(analyzeBidDocumentText(hwpText));

            // PDF/HWPX와 같은 키워드 및 URL 탐지 기준을 그대로 재사용한다.
            List<String> detectedKeywords = findExternalReferenceKeywords(hwpText);
            List<String> detectedExternalUrls = findExternalUrls(hwpText);
            boolean externalReferenceDetected =
                    !detectedKeywords.isEmpty() || !detectedExternalUrls.isEmpty();

            attachment.setAnalysisStatus("ANALYZED");
            attachment.setExternalReferenceDetected(externalReferenceDetected);
            attachment.setDetectedExternalUrls(detectedExternalUrls);
            attachment.setAnalysisReason(createHwpAnalysisReason(
                    detectedKeywords,
                    detectedExternalUrls
            ));
        } catch (Exception e) {
            attachment.setAnalysisStatus("FAILED");
            attachment.setExternalReferenceDetected(false);
            attachment.setDetectedExternalUrls(new ArrayList<>());
            String failureMessage = getSafeValue(e.getMessage()).trim();
            attachment.setDocumentAnalysis(createFailedDocumentAnalysis("HWP 본문 분석 실패", e));
            attachment.setAnalysisReason("HWP 분석 실패"
                    + (failureMessage.isEmpty() ? "" : ": " + failureMessage));
        }

        return attachment;
    }

    /**
     * 입찰공고의 면허, 참가가능지역 및 낙찰 관련 참가조건을 하나의 DTO로 조합한다.
     */
    public BidQualificationDto getBidQualification(String bidNtceNo) {
        return getBidQualification(bidNtceNo, DEFAULT_ALLOWED_LICENSE_CODES);
    }

    /**
     * 브라우저에서 전달한 허용 업종코드를 기준으로 특정 공고의 참가조건을 자동 판정한다.
     */
    public BidQualificationDto getBidQualification(String bidNtceNo, Set<String> allowedLicenseCodes) {
        // 공고번호 직접조회로 과거 공고를 포함한 기본 입찰정보를 가져온다.
        BidDetail bidDetail = getBidDetailByBidNtceNo(bidNtceNo);
        BidDto bidDto = bidDetail.bidDto();

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            // 면허제한 응답의 면허제한명들을 쉼표로 연결하며, 제한이 없으면 빈 문자열로 둔다.
            JsonNode licenseBody = objectMapper.readTree(getLicenseLimit(bidNtceNo))
                    .path("response")
                    .path("body");
            String licenseLimit = licenseBody.path("totalCount").asInt() == 0
                    ? ""
                    : getJoinedItemValues(licenseBody.path("items"), "lcnsLmtNm");

            // 명세의 참가가능지역명(prtcptPsblRgnNm)을 읽어 여러 지역은 쉼표로 연결한다.
            JsonNode regionBody = objectMapper.readTree(getParticipationRegion(bidNtceNo))
                    .path("response")
                    .path("body");
            String participationRegion = regionBody.path("totalCount").asInt() == 0
                    ? "제한없음"
                    : getJoinedItemValues(regionBody.path("items"), "prtcptPsblRgnNm");

            // 기본 공고정보와 상세 참가조건을 함께 담는다.
            BidQualificationDto qualification = new BidQualificationDto(
                    bidNtceNo,
                    licenseLimit,
                    participationRegion,
                    bidDto.getSucsfbidMthdNm(),
                    bidDto.getSucsfbidMthdCd(),
                    bidDto.getArsltCmptYn(),
                    bidDto.getPqEvalYn(),
                    bidDto.getTpEvalYn(),
                    bidDto.getCmmnSpldmdAgrmntRcptdocMethd()
            );

            // 이미 직접조회한 BidDto의 기본 공고정보를 함께 설정한다.
            qualification.setBidNtceNm(bidDto.getBidNtceNm());
            qualification.setNtceInsttNm(bidDto.getNtceInsttNm());
            qualification.setBidNtceDt(bidDto.getBidNtceDt());
            qualification.setBidClseDt(bidDto.getBidClseDt());
            qualification.setAsignBdgtAmt(bidDto.getAsignBdgtAmt());
            qualification.setBidNtceDtlUrl(bidDto.getBidNtceDtlUrl());

            // 공고 직접조회 응답에 포함된 첨부파일 정보를 DTO에 함께 보존한다.
            qualification.setAttachments(bidDetail.attachments());

            // 제한그룹번호를 보존하고 각 그룹의 항목을 제한순번 오름차순으로 정렬한다.
            qualification.setLicenseGroups(createLicenseGroups(licenseBody.path("items")));

            // 조합한 참가조건을 기준으로 자동 검토 상태와 판정 사유를 설정한다.
            applyReviewResult(qualification, normalizeAllowedLicenseCodes(allowedLicenseCodes));

            // 기존 참가조건 판정과 분리하여 첨부파일의 외부사이트 확인 상태를 공고 단위로 계산한다.
            applyExternalCheckResult(qualification);
            return qualification;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("참가조건 API 응답을 JSON으로 처리할 수 없습니다.", e);
        }
    }

    /** 분석 가능한 PDF/HWPX/HWP 첨부만 처리하고 기존 기준으로 공고 전체 결과를 계산한다. */
    private void applyExternalCheckResult(BidQualificationDto qualification) {
        List<BidAttachmentDto> attachments = qualification.getAttachments();
        qualification.setExternalSiteUrls(new ArrayList<>());

        if (attachments == null || attachments.isEmpty()) {
            setUnknownExternalCheckResult(qualification, "분석 가능한 PDF/HWPX/HWP 첨부파일이 없음");
            return;
        }

        List<BidAttachmentDto> analysisTargets = new ArrayList<>();
        for (BidAttachmentDto attachment : attachments) {
            if (attachment == null) {
                continue;
            }

            // 지정된 문서 유형의 PDF/HWPX/HWP만 내려받아 형식별 기존 분석 메서드로 처리한다.
            if (isPdfAnalysisTarget(attachment)) {
                analysisTargets.add(analyzePdfAttachment(attachment));
            } else if (isHwpxAnalysisTarget(attachment)) {
                analysisTargets.add(analyzeHwpxAttachment(attachment));
            } else if (isHwpAnalysisTarget(attachment)) {
                analysisTargets.add(analyzeHwpAttachment(attachment));
            }
        }

        if (analysisTargets.isEmpty()) {
            setUnknownExternalCheckResult(qualification, "분석 가능한 PDF/HWPX/HWP 첨부파일이 없음");
            return;
        }

        List<BidAttachmentDto> detectedAttachments = analysisTargets.stream()
                .filter(attachment -> Boolean.TRUE.equals(attachment.getExternalReferenceDetected()))
                .toList();

        // 여러 첨부에서 같은 외부 URL이 나온 경우 공고 단위 목록에는 한 번만 보존한다.
        Set<String> externalUrls = detectedAttachments.stream()
                .filter(attachment -> attachment.getDetectedExternalUrls() != null)
                .flatMap(attachment -> attachment.getDetectedExternalUrls().stream())
                .filter(url -> !getSafeValue(url).isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> referenceUrls = externalUrls.stream()
                .filter(this::isReferenceSiteUrl)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> requiredUrls = externalUrls.stream()
                .filter(url -> !isReferenceSiteUrl(url))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean keywordSignalDetected = detectedAttachments.stream()
                .anyMatch(this::hasExternalReferenceKeywordSignal);

        // 일반 외부 URL 또는 직접 제출 등의 키워드가 하나라도 있으면 참고사이트 포함 여부와 관계없이 REQUIRED다.
        if (!requiredUrls.isEmpty() || keywordSignalDetected) {
            List<String> detectedReasons = detectedAttachments.stream()
                    .map(this::createAttachmentExternalReason)
                    .filter(reason -> !reason.isBlank())
                    .distinct()
                    .toList();
            qualification.setExternalCheckStatus("REQUIRED");
            qualification.setExternalSiteCheckRequired(true);
            qualification.setExternalSiteUrls(new ArrayList<>(externalUrls));
            qualification.setExternalCheckReason(detectedReasons.isEmpty()
                    ? "첨부문서에서 외부사이트 확인 신호가 탐지됨"
                    : String.join(" | ", detectedReasons));
            return;
        }

        List<String> failedFileNames = analysisTargets.stream()
                .filter(attachment -> "FAILED".equals(attachment.getAnalysisStatus()))
                .map(attachment -> getSafeValue(attachment.getFileName()).trim())
                .filter(fileName -> !fileName.isEmpty())
                .distinct()
                .toList();

        // 일부라도 분석에 실패하면 실패한 문서에 외부 신호가 있을 수 있어 UNKNOWN으로 둔다.
        if (!failedFileNames.isEmpty()) {
            setUnknownExternalCheckResult(
                    qualification,
                    "첨부파일 분석 실패로 외부 확인 필요 여부를 판단할 수 없음: "
                            + String.join(", ", failedFileNames)
            );
            return;
        }

        // smpp.go.kr처럼 자격·제도 확인 목적의 사이트만 있으면 추가 공고 확인 대상으로 보지 않는다.
        if (!referenceUrls.isEmpty()) {
            qualification.setExternalCheckStatus("REFERENCE");
            qualification.setExternalSiteCheckRequired(false);
            qualification.setExternalSiteUrls(new ArrayList<>(referenceUrls));
            qualification.setExternalCheckReason(
                    "자격·제도 확인용 참고사이트가 포함되어 있음: " + String.join(", ", referenceUrls)
            );
            return;
        }

        boolean analyzedAttachmentExists = analysisTargets.stream()
                .anyMatch(attachment -> "ANALYZED".equals(attachment.getAnalysisStatus()));
        if (analyzedAttachmentExists) {
            qualification.setExternalCheckStatus("NOT_DETECTED");
            qualification.setExternalSiteCheckRequired(false);
            qualification.setExternalCheckReason("분석된 첨부파일에서 외부 홈페이지 확인 신호가 탐지되지 않음");
            return;
        }

        setUnknownExternalCheckResult(qualification, "첨부파일 분석 결과를 확인할 수 없음");
    }

    /** REQUIRED 사유에 첨부파일명과 해당 파일의 분석 사유를 함께 표시한다. */
    private String createAttachmentExternalReason(BidAttachmentDto attachment) {
        String fileName = getSafeValue(attachment.getFileName()).trim();
        String analysisReason = getSafeValue(attachment.getAnalysisReason()).trim();
        if (fileName.isEmpty()) {
            return analysisReason;
        }
        return analysisReason.isEmpty() ? fileName : fileName + ": " + analysisReason;
    }

    /** 첨부 분석 사유에 URL 이외의 외부 확인 키워드가 포함되었는지 확인한다. */
    private boolean hasExternalReferenceKeywordSignal(BidAttachmentDto attachment) {
        return getSafeValue(attachment.getAnalysisReason()).contains("탐지 키워드:");
    }

    /** 지정한 URL이 자격·제도 안내용 참고사이트 또는 그 하위 도메인인지 확인한다. */
    private boolean isReferenceSiteUrl(String url) {
        try {
            String host = getSafeValue(URI.create(url).getHost()).toLowerCase(Locale.ROOT);
            return REFERENCE_SITE_DOMAINS.stream()
                    .anyMatch(domain -> domain.equals(host) || host.endsWith("." + domain));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 외부사이트 확인 여부를 판단할 수 없는 공고의 공통 결과를 설정한다. */
    private void setUnknownExternalCheckResult(BidQualificationDto qualification, String reason) {
        qualification.setExternalCheckStatus("UNKNOWN");
        qualification.setExternalSiteCheckRequired(null);
        qualification.setExternalSiteUrls(new ArrayList<>());
        qualification.setExternalCheckReason(reason);
    }

    /**
     * 참가조건을 기준으로 공고의 자동 검토 상태와 사람이 확인할 판정 사유를 설정한다.
     */
    private void applyReviewResult(BidQualificationDto qualification, Set<String> allowedLicenseCodes) {
        String sucsfbidMthdCd = getSafeValue(qualification.getSucsfbidMthdCd());
        String sucsfbidMthdNm = getSafeValue(qualification.getSucsfbidMthdNm());

        // 협상에 의한 계약은 다른 조건과 관계없이 검토 대상에서 제외한다.
        if ("낙030005".equals(sucsfbidMthdCd) || sucsfbidMthdNm.contains("협상")) {
            qualification.setReviewStatus("제외");
            qualification.setReviewReason("협상에 의한 계약으로 대상 제외");
            return;
        }

        boolean smallAmountEstimate = "낙030029".equals(sucsfbidMthdCd)
                || sucsfbidMthdNm.contains("소액수의견적");
        boolean qualificationReview = "낙030001".equals(sucsfbidMthdCd)
                && sucsfbidMthdNm.contains("적격심사");

        // 소액수의견적 또는 적격심사제에 해당하지 않으면 자동 판정만으로는 검토 여부를 확정할 수 없다.
        if (!smallAmountEstimate && !qualificationReview) {
            qualification.setReviewStatus("추가확인필요");
            qualification.setReviewReason("낙찰방법이 검토대상 기준에 해당하는지 확인 필요");
            return;
        }

        List<String> additionalCheckReasons = new ArrayList<>();
        String participationRegion = getSafeValue(qualification.getParticipationRegion());

        // 검토대상 공고라도 면허, 지역 및 심사 조건이 있으면 추가 확인이 필요하다.
        LicenseReviewResult licenseReviewResult = reviewLicenseGroups(
                qualification.getLicenseGroups(),
                allowedLicenseCodes
        );
        if (!licenseReviewResult.satisfied()) {
            additionalCheckReasons.add(licenseReviewResult.reason());
        }
        if (!"제한없음".equals(participationRegion)) {
            additionalCheckReasons.add(participationRegion.isEmpty()
                    ? "지역제한 조건 확인 필요"
                    : "지역제한 조건 확인 필요: " + participationRegion);
        }
        if ("Y".equals(getSafeValue(qualification.getArsltCmptYn()))) {
            additionalCheckReasons.add("실적경쟁 조건 확인 필요");
        }
        if ("Y".equals(getSafeValue(qualification.getPqEvalYn()))) {
            additionalCheckReasons.add("PQ심사 조건 확인 필요");
        }
        if ("Y".equals(getSafeValue(qualification.getTpEvalYn()))) {
            additionalCheckReasons.add("TP심사 조건 확인 필요");
        }

        if (!additionalCheckReasons.isEmpty()) {
            qualification.setReviewStatus("추가확인필요");
            qualification.setReviewReason(String.join(", ", additionalCheckReasons));
            return;
        }

        qualification.setReviewStatus("검토대상");
        qualification.setReviewReason((smallAmountEstimate ? "소액수의견적" : "적격심사제")
                + ", 허용 면허조건 충족, 지역제한 없음");
    }

    /**
     * 같은 그룹의 면허는 모두 충족(AND), 여러 그룹 중 하나만 충족하면 통과(OR)하도록 판정한다.
     * 감리 대상 판정이므로 통과 그룹에는 필수 업종코드 6146이 반드시 포함되어야 한다.
     */
    private LicenseReviewResult reviewLicenseGroups(
            List<LicenseRequirementGroup> licenseGroups,
            Set<String> allowedLicenseCodes
    ) {
        List<LicenseRequirementGroup> safeGroups = licenseGroups == null ? List.of() : licenseGroups;
        List<String> closestMissingCodes = null;
        boolean hasRequiredLicense = false;
        boolean hasUnknownLicenseCode = false;

        for (LicenseRequirementGroup group : safeGroups) {
            List<LicenseRequirement> requirements = group == null || group.getRequirements() == null
                    ? List.of()
                    : group.getRequirements();
            boolean groupHasRequiredLicense = requirements.stream()
                    .filter(requirement -> requirement != null)
                    .map(LicenseRequirement::getLicenseCode)
                    .map(this::getSafeValue)
                    .anyMatch(REQUIRED_LICENSE_CODE::equals);

            hasRequiredLicense |= groupHasRequiredLicense;
            if (!groupHasRequiredLicense || requirements.isEmpty()) {
                continue;
            }

            // 한 그룹 안에서 허용되지 않은 코드를 모두 모아 AND 조건 충족 여부를 확인한다.
            LinkedHashSet<String> missingCodes = new LinkedHashSet<>();
            boolean groupHasUnknownLicenseCode = false;
            for (LicenseRequirement requirement : requirements) {
                String licenseCode = requirement == null
                        ? ""
                        : getSafeValue(requirement.getLicenseCode()).trim();
                if (licenseCode.isEmpty()) {
                    groupHasUnknownLicenseCode = true;
                } else if (!allowedLicenseCodes.contains(licenseCode)) {
                    missingCodes.add(licenseCode);
                }
            }

            if (missingCodes.isEmpty() && !groupHasUnknownLicenseCode) {
                return new LicenseReviewResult(true, "");
            }

            hasUnknownLicenseCode |= groupHasUnknownLicenseCode;
            if (!missingCodes.isEmpty()
                    && (closestMissingCodes == null || missingCodes.size() < closestMissingCodes.size())) {
                closestMissingCodes = new ArrayList<>(missingCodes);
            }
        }

        if (!hasRequiredLicense) {
            return new LicenseReviewResult(false, "6146 면허조건 확인 필요");
        }
        if (closestMissingCodes != null) {
            return new LicenseReviewResult(
                    false,
                    "추가 면허조건 확인 필요: " + String.join(", ", closestMissingCodes)
            );
        }
        if (hasUnknownLicenseCode) {
            return new LicenseReviewResult(false, "면허조건 코드 확인 필요");
        }
        return new LicenseReviewResult(false, "허용 면허조건 확인 필요");
    }

    /**
     * 전달값이 없으면 기본 허용코드를 사용하고, 공백값과 중복값은 제거한다.
     */
    private Set<String> normalizeAllowedLicenseCodes(Set<String> allowedLicenseCodes) {
        if (allowedLicenseCodes == null || allowedLicenseCodes.isEmpty()) {
            return DEFAULT_ALLOWED_LICENSE_CODES;
        }

        Set<String> normalizedCodes = allowedLicenseCodes.stream()
                .map(this::getSafeValue)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return normalizedCodes.isEmpty() ? DEFAULT_ALLOWED_LICENSE_CODES : normalizedCodes;
    }

    /**
     * API 응답의 누락값을 빈 문자열로 바꿔 null 비교와 문자열 검사 시 예외를 방지한다.
     */
    private String getSafeValue(String value) {
        return value == null ? "" : value;
    }

    /**
     * 면허제한 API 항목을 제한그룹번호별 구조로 변환한다.
     */
    private List<LicenseRequirementGroup> createLicenseGroups(JsonNode items) {
        // 숫자 형태의 그룹번호가 자연스러운 오름차순으로 정렬되도록 TreeMap을 사용한다.
        Map<String, List<LicenseRequirement>> requirementsByGroup =
                new TreeMap<>(this::compareNumericText);

        for (JsonNode item : items) {
            String groupNo = item.path("lmtGrpNo").asText();
            String sequence = item.path("lmtSno").asText();
            String rawLicenseLimitName = item.path("lcnsLmtNm").asText();

            // 마지막 슬래시를 기준으로 면허명과 업종·면허 코드를 분리한다.
            int codeSeparatorIndex = rawLicenseLimitName.lastIndexOf('/');
            String licenseName = codeSeparatorIndex < 0
                    ? rawLicenseLimitName.trim()
                    : rawLicenseLimitName.substring(0, codeSeparatorIndex).trim();
            String licenseCode = codeSeparatorIndex < 0
                    ? ""
                    : rawLicenseLimitName.substring(codeSeparatorIndex + 1).trim();

            LicenseRequirement requirement = new LicenseRequirement(
                    sequence,
                    licenseCode,
                    licenseName,
                    rawLicenseLimitName
            );
            requirementsByGroup.computeIfAbsent(groupNo, key -> new ArrayList<>())
                    .add(requirement);
        }

        List<LicenseRequirementGroup> licenseGroups = new ArrayList<>();
        for (Map.Entry<String, List<LicenseRequirement>> entry : requirementsByGroup.entrySet()) {
            // API 배열 순서와 관계없이 각 그룹 내부를 lmtSno 기준으로 정렬한다.
            entry.getValue().sort(Comparator.comparing(
                    LicenseRequirement::getSequence,
                    this::compareNumericText
            ));
            licenseGroups.add(new LicenseRequirementGroup(entry.getKey(), entry.getValue()));
        }

        return licenseGroups;
    }

    /**
     * 숫자로 표현된 그룹번호와 순번을 숫자 기준으로 비교하고, 숫자가 아니면 문자열로 비교한다.
     */
    private int compareNumericText(String left, String right) {
        String safeLeft = getSafeValue(left);
        String safeRight = getSafeValue(right);

        try {
            return Integer.compare(Integer.parseInt(safeLeft), Integer.parseInt(safeRight));
        } catch (NumberFormatException e) {
            return safeLeft.compareTo(safeRight);
        }
    }

    /**
     * 용역 입찰공고를 공고번호로 직접 조회해 기본정보와 첨부파일 목록으로 변환한다.
     */
    private BidDetail getBidDetailByBidNtceNo(String bidNtceNo) {
        // inqryDiv=2는 나라장터 명세에서 입찰공고번호 기준 조회를 의미한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServc"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();
        String responseBody = restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            JsonNode items = objectMapper.readTree(responseBody)
                    .path("response")
                    .path("body")
                    .path("items");

            // 직접조회 결과에서 요청한 공고번호와 일치하는 공고를 선택한다.
            for (JsonNode item : items) {
                if (bidNtceNo.equals(item.path("bidNtceNo").asText())) {
                    BidDto bidDto = new BidDto(
                            item.path("bidNtceNo").asText(),
                            item.path("bidNtceNm").asText(),
                            item.path("ntceInsttNm").asText(),
                            item.path("bidNtceDt").asText(),
                            item.path("bidClseDt").asText(),
                            item.path("asignBdgtAmt").asText(),
                            item.path("sucsfbidMthdNm").asText(),
                            item.path("bidNtceDtlUrl").asText(),
                            item.path("sucsfbidMthdCd").asText(),
                            item.path("techAbltEvlRt").asText(),
                            item.path("bidPrceEvlRt").asText(),
                            item.path("sucsfbidMthdAppStd").asText(),
                            item.path("arsltCmptYn").asText(),
                            item.path("pqEvalYn").asText(),
                            item.path("tpEvalYn").asText(),
                            item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
                    );
                    return new BidDetail(bidDto, createBidAttachments(item));
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("입찰공고 직접조회 응답을 JSON으로 처리할 수 없습니다.", e);
        }

        throw new IllegalArgumentException("입력한 공고번호에 해당하는 입찰공고를 찾을 수 없습니다: " + bidNtceNo);
    }

    /**
     * 공고 직접조회 응답의 표준공고서와 공고규격서 정보를 첨부파일 목록으로 변환한다.
     */
    private List<BidAttachmentDto> createBidAttachments(JsonNode item) {
        // URL을 키로 사용해 표준공고서와 공고규격서에 중복 등록된 파일을 한 번만 보존한다.
        Map<String, BidAttachmentDto> attachmentsByUrl = new LinkedHashMap<>();

        for (int index = 1; index <= 10; index++) {
            String fileName = item.path("ntceSpecFileNm" + index).asText().trim();
            String fileUrl = item.path("ntceSpecDocUrl" + index).asText().trim();

            // 파일명과 URL이 모두 있는 정상적인 첨부 항목만 저장한다.
            if (fileName.isEmpty() || fileUrl.isEmpty()) {
                continue;
            }

            attachmentsByUrl.putIfAbsent(
                    fileUrl,
                    new BidAttachmentDto(
                            fileName,
                            fileUrl,
                            classifyDocumentType(fileName),
                            "NOT_ANALYZED"
                    )
            );
        }

        String standardNoticeDocumentUrl = item.path("stdNtceDocUrl").asText().trim();
        if (!standardNoticeDocumentUrl.isEmpty()) {
            // 표준공고서에는 별도 파일명 필드가 없으므로 고정 표시명을 사용한다.
            attachmentsByUrl.putIfAbsent(
                    standardNoticeDocumentUrl,
                    new BidAttachmentDto(
                            "표준공고서",
                            standardNoticeDocumentUrl,
                            "공고문",
                            "NOT_ANALYZED"
                    )
            );
        }

        return new ArrayList<>(attachmentsByUrl.values());
    }

    /**
     * 첨부파일명에 포함된 대표 문서명을 기준으로 문서 종류를 우선 분류한다.
     */
    private String classifyDocumentType(String fileName) {
        String safeFileName = getSafeValue(fileName);
        if (safeFileName.contains("과업지시")) {
            return "과업지시서";
        }
        if (safeFileName.contains("제안요청")) {
            return "제안요청서";
        }
        if (safeFileName.contains("공고")) {
            return "공고문";
        }
        return "기타";
    }

    /** PDF이면서 공고문·과업지시서·제안요청서로 분류된 첨부만 분석 대상으로 선택한다. */
    private boolean isPdfAnalysisTarget(BidAttachmentDto attachment) {
        String fileName = getSafeValue(attachment.getFileName()).trim();
        String fileUrl = getSafeValue(attachment.getFileUrl()).trim();
        String documentType = getSafeValue(attachment.getDocumentType()).trim();
        return !fileUrl.isEmpty()
                && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                && PDF_ANALYSIS_DOCUMENT_TYPES.contains(documentType);
    }

    /** HWPX이면서 공고문·과업지시서·제안요청서로 분류된 첨부만 분석 대상으로 선택한다. */
    private boolean isHwpxAnalysisTarget(BidAttachmentDto attachment) {
        String fileName = getSafeValue(attachment.getFileName()).trim();
        String fileUrl = getSafeValue(attachment.getFileUrl()).trim();
        String documentType = getSafeValue(attachment.getDocumentType()).trim();
        return !fileUrl.isEmpty()
                && fileName.toLowerCase(Locale.ROOT).endsWith(".hwpx")
                && HWPX_ANALYSIS_DOCUMENT_TYPES.contains(documentType);
    }

    /** HWP이면서 공고문·과업지시서·제안요청서로 분류된 첨부만 분석 대상으로 선택한다. */
    private boolean isHwpAnalysisTarget(BidAttachmentDto attachment) {
        String fileName = getSafeValue(attachment.getFileName()).trim();
        String fileUrl = getSafeValue(attachment.getFileUrl()).trim();
        String documentType = getSafeValue(attachment.getDocumentType()).trim();
        return !fileUrl.isEmpty()
                && fileName.toLowerCase(Locale.ROOT).endsWith(".hwp")
                && HWP_ANALYSIS_DOCUMENT_TYPES.contains(documentType);
    }

    /** 연결·응답 시간과 최대 크기를 제한해 분석할 HWP 파일을 안전하게 내려받는다. */
    private byte[] downloadHwpAttachment(String fileUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(fileUrl).toURL().openConnection();
        connection.setConnectTimeout(HWP_CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(HWP_READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");

        try {
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HWP 다운로드 응답 코드가 정상 범위가 아닙니다: " + statusCode);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_HWP_DOWNLOAD_BYTES) {
                throw new IOException("HWP 파일 크기가 분석 허용 범위를 초과했습니다.");
            }

            try (InputStream inputStream = connection.getInputStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int totalBytes = 0;
                int readBytes;
                while ((readBytes = inputStream.read(buffer)) != -1) {
                    totalBytes += readBytes;
                    if (totalBytes > MAX_HWP_DOWNLOAD_BYTES) {
                        throw new IOException("HWP 파일 크기가 분석 허용 범위를 초과했습니다.");
                    }
                    outputStream.write(buffer, 0, readBytes);
                }
                if (totalBytes == 0) {
                    throw new IOException("다운로드한 HWP 파일이 비어 있습니다.");
                }
                return outputStream.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 확장자만 신뢰하지 않고 OLE 복합문서 및 HWP 5.x 내부 서명을 함께 검증한다. */
    private void validateHwpSignature(byte[] hwpBytes) throws IOException {
        if (!hasByteSequenceAt(hwpBytes, OLE_COMPOUND_FILE_SIGNATURE, 0)
                || !containsByteSequence(hwpBytes, HWP_DOCUMENT_FILE_SIGNATURE)) {
            throw new IOException("HWP 5.x 파일 서명을 확인할 수 없습니다.");
        }
    }

    private boolean hasByteSequenceAt(byte[] source, byte[] expected, int offset) {
        if (source == null || expected == null || offset < 0 || source.length - offset < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (source[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsByteSequence(byte[] source, byte[] expected) {
        if (source == null || expected == null || source.length < expected.length) {
            return false;
        }
        for (int offset = 0; offset <= source.length - expected.length; offset++) {
            if (hasByteSequenceAt(source, expected, offset)) {
                return true;
            }
        }
        return false;
    }

    /** hwplib 분석을 제한된 작업 스레드에서 실행해 비정상 문서가 요청을 무기한 점유하지 않게 한다. */
    private String extractHwpTextWithTimeout(byte[] hwpBytes) throws Exception {
        Future<String> extractionTask = HWP_ANALYSIS_EXECUTOR.submit(() -> {
            HWPFile hwpFile = HWPReader.fromInputStream(new ByteArrayInputStream(hwpBytes));
            String extractedText = TextExtractor.extract(
                    hwpFile,
                    TextExtractMethod.InsertControlTextBetweenParagraphText
            );
            if (extractedText.length() > MAX_HWP_EXTRACTED_TEXT_LENGTH) {
                throw new IOException("HWP 추출 텍스트 길이가 분석 허용 범위를 초과했습니다.");
            }
            return extractedText;
        });

        try {
            return extractionTask.get(HWP_ANALYSIS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            extractionTask.cancel(true);
            throw new IOException("HWP 본문 분석 제한 시간을 초과했습니다.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("HWP 본문 분석 중 알 수 없는 오류가 발생했습니다.", cause);
        } catch (InterruptedException e) {
            extractionTask.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("HWP 본문 분석이 중단되었습니다.", e);
        }
    }

    /** HWPX ZIP의 Contents/section*.xml 본문을 순회하며 텍스트를 추출한다. */
    private String extractHwpxText(byte[] hwpxBytes) throws Exception {
        StringBuilder extractedText = new StringBuilder();
        int totalSectionXmlBytes = 0;
        int sectionCount = 0;

        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(hwpxBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                String entryName = zipEntry.getName().replace('\\', '/');
                if (!zipEntry.isDirectory() && HWPX_SECTION_XML_PATTERN.matcher(entryName).matches()) {
                    byte[] sectionXml = readHwpxSectionXml(zipInputStream);
                    totalSectionXmlBytes += sectionXml.length;
                    if (totalSectionXmlBytes > MAX_HWPX_SECTION_XML_BYTES) {
                        throw new IOException("HWPX 본문 XML 크기가 분석 허용 범위를 초과했습니다.");
                    }
                    extractedText.append(extractHwpxSectionText(sectionXml)).append('\n');
                    sectionCount++;
                }
                zipInputStream.closeEntry();
            }
        }

        if (sectionCount == 0) {
            throw new IOException("HWPX 압축파일에서 Contents/section XML을 찾지 못했습니다.");
        }
        return extractedText.toString();
    }

    /** 비정상적으로 큰 압축 항목으로 인한 메모리 사용을 막으며 현재 section XML을 읽는다. */
    private byte[] readHwpxSectionXml(ZipInputStream zipInputStream) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int readBytes;
            int sectionBytes = 0;
            while ((readBytes = zipInputStream.read(buffer)) != -1) {
                sectionBytes += readBytes;
                if (sectionBytes > MAX_HWPX_SECTION_XML_BYTES) {
                    throw new IOException("HWPX section XML 크기가 분석 허용 범위를 초과했습니다.");
                }
                outputStream.write(buffer, 0, readBytes);
            }
            return outputStream.toByteArray();
        }
    }

    /** XML 외부 엔티티를 차단한 StAX 파서로 hp:t 요소의 본문 문자열을 읽는다. */
    private String extractHwpxSectionText(byte[] sectionXml) throws Exception {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

        StringBuilder sectionText = new StringBuilder();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(sectionXml)) {
            XMLStreamReader reader = inputFactory.createXMLStreamReader(
                    inputStream,
                    StandardCharsets.UTF_8.name()
            );
            boolean insideTextElement = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String localName = reader.getLocalName();
                        if ("t".equals(localName)) {
                            insideTextElement = true;
                        } else if (insideTextElement && "tab".equals(localName)) {
                            sectionText.append(' ');
                        } else if (insideTextElement && "lineBreak".equals(localName)) {
                            sectionText.append('\n');
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) && insideTextElement) {
                        sectionText.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String localName = reader.getLocalName();
                        if ("t".equals(localName)) {
                            insideTextElement = false;
                        } else if ("p".equals(localName)) {
                            sectionText.append('\n');
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }
        return sectionText.toString();
    }

    /**
     * 추출된 문서 원문에서 지정된 제목과 키워드 주변의 문장·목록만 구조화한다.
     * 생성형 요약을 하지 않고 줄바꿈과 반복 공백만 정리한 원문 조각을 그대로 보존한다.
     */
    BidDocumentAnalysisDto analyzeBidDocumentText(String documentText) {
        BidDocumentAnalysisDto analysis = new BidDocumentAnalysisDto();
        if (getSafeValue(documentText).isBlank()) {
            analysis.setAnalysisStatus("FAILED");
            analysis.setAnalysisNote("문서 본문 텍스트가 없어 핵심정보를 분석할 수 없음");
            return analysis;
        }

        List<String> lines = getNormalizedDocumentLines(documentText);
        Set<String> requiredDocuments = new LinkedHashSet<>();
        Set<String> qualificationRequirements = new LinkedHashSet<>();
        Set<String> submissionMethods = new LinkedHashSet<>();
        Set<String> submissionDeadlines = new LinkedHashSet<>();
        Set<String> jointContractRequirements = new LinkedHashSet<>();

        // 제출서류와 참가자격은 번호가 붙은 제목 아래의 목록을 다음 번호 제목 전까지 수집한다.
        collectHeadingSections(lines, REQUIRED_DOCUMENT_HEADINGS, requiredDocuments, true);
        collectHeadingSections(lines, QUALIFICATION_HEADINGS, qualificationRequirements, false);

        // 뚜렷한 제목이 없는 문서에서는 키워드가 들어간 원문 줄 자체를 보존한다.
        if (requiredDocuments.isEmpty()) {
            collectKeywordLines(lines, REQUIRED_DOCUMENT_HEADINGS, requiredDocuments);
        }
        if (qualificationRequirements.isEmpty()) {
            collectKeywordLines(lines, QUALIFICATION_HEADINGS, qualificationRequirements);
        }

        collectSubmissionMethodLines(lines, submissionMethods);
        collectSubmissionDeadlineLines(lines, submissionDeadlines);
        collectJointContractLines(lines, jointContractRequirements);

        analysis.setRequiredDocuments(new ArrayList<>(requiredDocuments));
        analysis.setQualificationRequirements(new ArrayList<>(qualificationRequirements));
        analysis.setSubmissionMethods(new ArrayList<>(submissionMethods));
        analysis.setSubmissionDeadlines(new ArrayList<>(submissionDeadlines));
        analysis.setJointContractRequirements(new ArrayList<>(jointContractRequirements));
        analysis.setAnalysisStatus("ANALYZED");

        int detectedItemCount = requiredDocuments.size()
                + qualificationRequirements.size()
                + submissionMethods.size()
                + submissionDeadlines.size()
                + jointContractRequirements.size();
        analysis.setAnalysisNote(detectedItemCount == 0
                ? "분석은 완료되었으나 지정 핵심정보가 탐지되지 않음"
                : "문서 핵심정보 추출 완료: 제출서류 " + requiredDocuments.size()
                        + "건, 참가자격 " + qualificationRequirements.size()
                        + "건, 제출방법 " + submissionMethods.size()
                        + "건, 제출기한 " + submissionDeadlines.size()
                        + "건, 공동수급 " + jointContractRequirements.size() + "건");
        return analysis;
    }

    /** 빈 줄을 제외하고 같은 문장이 비교 가능하도록 반복 공백만 하나로 정리한다. */
    private List<String> getNormalizedDocumentLines(String documentText) {
        return getSafeValue(documentText).lines()
                .map(line -> line.replaceAll("\\s+", " ").trim())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** 지정 제목을 찾으면 다음 최상위 번호 제목 전까지의 원문 목록을 수집한다. */
    private void collectHeadingSections(
            List<String> lines,
            List<String> headings,
            Set<String> destination,
            boolean splitDocumentList
    ) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!isCategoryHeading(line, headings)) {
                continue;
            }

            List<String> sectionLines = new ArrayList<>();
            for (int sectionIndex = index + 1; sectionIndex < lines.size(); sectionIndex++) {
                String sectionLine = lines.get(sectionIndex);
                if (isTopLevelSectionHeading(sectionLine)) {
                    break;
                }
                sectionLines.add(sectionLine);
            }

            if (splitDocumentList) {
                sectionLines.forEach(sectionLine -> addRequiredDocumentLine(sectionLine, destination));
            } else {
                addGroupedSectionLines(sectionLines, destination);
            }
        }
    }

    /** 표에서 한 줄로 합쳐진 제출서류는 원문의 목록 구분자(･) 단위로만 나누어 저장한다. */
    private void addRequiredDocumentLine(String line, Set<String> destination) {
        String[] listItems = line.split("\\s*･\\s*");
        if (listItems.length == 1) {
            if (!isRequiredDocumentNoise(line)) {
                destination.add(line);
            }
            return;
        }
        for (int index = 1; index < listItems.length; index++) {
            String item = listItems[index].trim();
            if (!item.isEmpty() && !isRequiredDocumentNoise(item)) {
                destination.add(item);
            }
        }
    }

    /** PDF에서 줄바꿈된 하나의 자격 문장을 목록 기호 기준으로 다시 연결한다. */
    private void addGroupedSectionLines(List<String> sectionLines, Set<String> destination) {
        StringBuilder currentItem = new StringBuilder();
        for (String line : sectionLines) {
            if (isPageNumberLine(line)) {
                continue;
            }
            if (isListItemStart(line) && currentItem.length() > 0) {
                destination.add(currentItem.toString());
                currentItem.setLength(0);
            }
            if (currentItem.length() > 0) {
                currentItem.append(' ');
            }
            currentItem.append(line);
        }
        if (currentItem.length() > 0) {
            destination.add(currentItem.toString());
        }
    }

    private boolean isListItemStart(String line) {
        String safeLine = getSafeValue(line).trim();
        return safeLine.matches("^[ㅇ○●▪■□※*·-].*");
    }

    private boolean isPageNumberLine(String line) {
        return getSafeValue(line).trim().matches("^-\\s*\\d+\\s*-$");
    }

    /** 표 머리글·분류명·페이지 번호처럼 제출서류 자체가 아닌 항목은 제외한다. */
    private boolean isRequiredDocumentNoise(String line) {
        String compactLine = getSafeValue(line).replaceAll("\\s+", "");
        return compactLine.isEmpty()
                || compactLine.matches("^-?\\d+-?$")
                || Set.of("구분내용수량", "제안업체", "일반사항", "자체서식").contains(compactLine);
    }

    private void collectKeywordLines(
            List<String> lines,
            List<String> keywords,
            Set<String> destination
    ) {
        lines.stream()
                .filter(line -> containsAnyKeyword(line, keywords))
                .forEach(destination::add);
    }

    /** 제출처·우편·직접 제출 등 실제 제출 행위를 나타내는 원문 줄을 수집한다. */
    private void collectSubmissionMethodLines(List<String> lines, Set<String> destination) {
        for (String line : lines) {
            if (containsAnyKeyword(line, SUBMISSION_METHOD_KEYWORDS)
                    && !isTopLevelSectionHeading(line)) {
                destination.add(line);
            }
        }
    }

    /** 마감 키워드가 있는 줄과 바로 뒤에 이어지는 날짜·시간 원문 줄을 수집한다. */
    private void collectSubmissionDeadlineLines(List<String> lines, Set<String> destination) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!containsAnyKeyword(line, SUBMISSION_DEADLINE_KEYWORDS)) {
                continue;
            }
            destination.add(line);

            // 표 제목과 실제 날짜가 분리된 경우에만 가까운 날짜 줄을 추가한다.
            if (!DEADLINE_VALUE_PATTERN.matcher(line).matches()) {
                int lastCandidateIndex = Math.min(index + 4, lines.size() - 1);
                for (int candidateIndex = index + 1;
                     candidateIndex <= lastCandidateIndex;
                     candidateIndex++) {
                    String candidate = lines.get(candidateIndex);
                    if (isTopLevelSectionHeading(candidate)) {
                        break;
                    }
                    if (DEADLINE_VALUE_PATTERN.matcher(candidate).matches()) {
                        destination.add(candidate);
                    }
                }
            }
        }
    }

    /** 공동수급 제목이 있으면 해당 절을, 일반 문장이라면 키워드가 있는 줄만 보존한다. */
    private void collectJointContractLines(List<String> lines, Set<String> destination) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!containsAnyKeyword(line, JOINT_CONTRACT_KEYWORDS)) {
                continue;
            }

            if (isTopLevelSectionHeading(line)) {
                continue;
            }

            // PDF 줄바꿈으로 이어진 문장은 다음 목록·제목 전까지 최대 세 줄만 연결한다.
            StringBuilder requirement = new StringBuilder(line);
            int lastContinuationIndex = Math.min(index + 3, lines.size() - 1);
            for (int continuationIndex = index + 1;
                 continuationIndex <= lastContinuationIndex;
                 continuationIndex++) {
                String continuation = lines.get(continuationIndex);
                if (isTopLevelSectionHeading(continuation)
                        || isListItemStart(continuation)
                        || continuation.matches("^[가-하]\\s*[.)].*")) {
                    break;
                }
                requirement.append(' ').append(continuation);
            }
            destination.add(requirement.toString());
        }
    }

    private boolean isCategoryHeading(String line, List<String> headings) {
        return containsAnyKeyword(line, headings)
                && (isTopLevelSectionHeading(line) || line.length() <= 40);
    }

    private boolean isTopLevelSectionHeading(String line) {
        return TOP_LEVEL_SECTION_HEADING_PATTERN.matcher(getSafeValue(line)).matches();
    }

    private boolean containsAnyKeyword(String line, List<String> keywords) {
        String safeLine = getSafeValue(line);
        return keywords.stream().anyMatch(safeLine::contains);
    }

    /** 형식 파싱 실패와 단순 미탐지가 구분되도록 문서 분석 실패 DTO를 만든다. */
    private BidDocumentAnalysisDto createFailedDocumentAnalysis(String reason, Exception exception) {
        BidDocumentAnalysisDto analysis = new BidDocumentAnalysisDto();
        analysis.setAnalysisStatus("FAILED");
        String failureMessage = getSafeValue(exception == null ? "" : exception.getMessage()).trim();
        analysis.setAnalysisNote(reason + (failureMessage.isEmpty() ? "" : ": " + failureMessage));
        return analysis;
    }

    /** PDF 본문에서 외부 확인 가능성을 나타내는 한글 표현을 찾는다. */
    private List<String> findExternalReferenceKeywords(String pdfText) {
        String safePdfText = getSafeValue(pdfText);
        List<String> detectedKeywords = new ArrayList<>();

        for (String keyword : EXTERNAL_REFERENCE_KEYWORDS) {
            if (safePdfText.contains(keyword)) {
                // 긴 표현이 이미 탐지된 경우 그 안에 포함된 짧은 표현은 중복 사유로 기록하지 않는다.
                boolean includedInDetectedKeyword = detectedKeywords.stream()
                        .anyMatch(detectedKeyword -> detectedKeyword.contains(keyword));
                if (!includedInDetectedKeyword) {
                    detectedKeywords.add(keyword);
                }
            }
        }
        return detectedKeywords;
    }

    /** PDF 본문의 HTTP URL 중 나라장터 도메인을 제외한 외부 URL만 중복 없이 수집한다. */
    private List<String> findExternalUrls(String pdfText) {
        Matcher matcher = HTTP_URL_PATTERN.matcher(getSafeValue(pdfText));
        Set<String> externalUrls = new LinkedHashSet<>();

        while (matcher.find()) {
            String detectedUrl = removeTrailingUrlPunctuation(matcher.group());
            try {
                String host = getSafeValue(URI.create(detectedUrl).getHost()).toLowerCase(Locale.ROOT);
                if (!host.isEmpty() && !isG2bHost(host)) {
                    externalUrls.add(detectedUrl);
                }
            } catch (IllegalArgumentException ignored) {
                // PDF 줄바꿈 등으로 손상된 URL은 외부 주소 목록에 포함하지 않는다.
            }
        }
        return new ArrayList<>(externalUrls);
    }

    /** URL 뒤에 문장부호가 붙어 추출되는 경우 주소 부분만 남긴다. */
    private String removeTrailingUrlPunctuation(String url) {
        String trimmedUrl = getSafeValue(url);
        String trailingPunctuation = ".,;:!?)]}>\"'”’";
        while (!trimmedUrl.isEmpty()
                && trailingPunctuation.indexOf(trimmedUrl.charAt(trimmedUrl.length() - 1)) >= 0) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 1);
        }
        return trimmedUrl;
    }

    /** 나라장터 자체 URL은 외부 확인 주소에서 제외한다. */
    private boolean isG2bHost(String host) {
        return "g2b.go.kr".equals(host) || host.endsWith(".g2b.go.kr");
    }

    /** 탐지 결과를 사람이 바로 이해할 수 있는 분석 사유로 만든다. */
    private String createPdfAnalysisReason(
            String pdfText,
            List<String> detectedKeywords,
            List<String> detectedExternalUrls
    ) {
        if (getSafeValue(pdfText).isBlank()) {
            return "PDF에서 텍스트를 추출하지 못해 외부 확인 신호를 판정할 수 없음";
        }
        if (detectedKeywords.isEmpty() && detectedExternalUrls.isEmpty()) {
            return "외부 홈페이지 확인 신호가 탐지되지 않음";
        }

        List<String> reasons = new ArrayList<>();
        if (!detectedKeywords.isEmpty()) {
            reasons.add("탐지 키워드: " + String.join(", ", detectedKeywords));
        }
        if (!detectedExternalUrls.isEmpty()) {
            reasons.add("외부 URL: " + String.join(", ", detectedExternalUrls));
        }
        return String.join(" / ", reasons);
    }

    /** HWPX에서 탐지된 키워드와 외부 URL을 사람이 확인하기 쉬운 사유로 만든다. */
    private String createHwpxAnalysisReason(
            List<String> detectedKeywords,
            List<String> detectedExternalUrls
    ) {
        if (detectedKeywords.isEmpty() && detectedExternalUrls.isEmpty()) {
            return "외부 홈페이지 확인 신호가 탐지되지 않음";
        }

        List<String> reasons = new ArrayList<>();
        if (!detectedKeywords.isEmpty()) {
            reasons.add("탐지 키워드: " + String.join(", ", detectedKeywords));
        }
        if (!detectedExternalUrls.isEmpty()) {
            reasons.add("외부 URL: " + String.join(", ", detectedExternalUrls));
        }
        return String.join(" / ", reasons);
    }

    /** HWP에서 탐지된 키워드와 외부 URL을 기존 형식과 같은 분석 사유로 만든다. */
    private String createHwpAnalysisReason(
            List<String> detectedKeywords,
            List<String> detectedExternalUrls
    ) {
        if (detectedKeywords.isEmpty() && detectedExternalUrls.isEmpty()) {
            return "외부 홈페이지 확인 신호가 탐지되지 않음";
        }

        List<String> reasons = new ArrayList<>();
        if (!detectedKeywords.isEmpty()) {
            reasons.add("탐지 키워드: " + String.join(", ", detectedKeywords));
        }
        if (!detectedExternalUrls.isEmpty()) {
            reasons.add("외부 URL: " + String.join(", ", detectedExternalUrls));
        }
        return String.join(" / ", reasons);
    }

    /**
     * 응답 items 배열의 지정한 필드값을 빈 값 없이 쉼표로 연결한다.
     */
    private String getJoinedItemValues(JsonNode items, String fieldName) {
        List<String> values = new ArrayList<>();

        for (JsonNode item : items) {
            String value = item.path(fieldName).asText();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }

        return String.join(",", values);
    }

    /**
     * 나라장터 응답의 입찰공고 항목만 BidDto 목록으로 변환한다.
     */
    public List<BidDto> getBidDtoList() {
        // 기존 API 호출 결과를 JSON 트리로 읽어 필요한 배열 경로만 선택한다.
        String responseBody = getBidList();
        ObjectMapper objectMapper = new ObjectMapper();
        List<BidDto> bidList = new ArrayList<>();

        try {
            JsonNode items = objectMapper.readTree(responseBody)
                    .path("response")
                    .path("body")
                    .path("items");

            // 각 입찰공고에서 화면에 필요한 필드만 BidDto에 담는다.
            for (JsonNode item : items) {
                bidList.add(new BidDto(
                        item.path("bidNtceNo").asText(),
                        item.path("bidNtceNm").asText(),
                        item.path("ntceInsttNm").asText(),
                        item.path("bidNtceDt").asText(),
                        item.path("bidClseDt").asText(),
                        item.path("asignBdgtAmt").asText(),
                        item.path("sucsfbidMthdNm").asText(),
                        item.path("bidNtceDtlUrl").asText(),
                        // 낙찰방법 및 심사 조건을 자동 판단에 사용할 수 있도록 함께 담는다.
                        item.path("sucsfbidMthdCd").asText(),
                        item.path("techAbltEvlRt").asText(),
                        item.path("bidPrceEvlRt").asText(),
                        item.path("sucsfbidMthdAppStd").asText(),
                        item.path("arsltCmptYn").asText(),
                        item.path("pqEvalYn").asText(),
                        item.path("tpEvalYn").asText(),
                        item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
                ));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("나라장터 API 응답을 JSON으로 변환할 수 없습니다.", e);
        }

        return bidList;
    }

    /**
     * 지정한 기간의 입찰공고 응답을 BidDto 목록으로 변환한다.
     */
    public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("조회 시작일과 종료일을 모두 입력해야 합니다.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        }

        // 공고번호 삽입 순서를 유지하면서 구간 경계 등에서 발생할 수 있는 중복을 제거한다.
        Map<String, BidDto> uniqueBids = new LinkedHashMap<>();
        LocalDate chunkStartDate = startDate;

        while (!chunkStartDate.isAfter(endDate)) {
            // 나라장터의 조회 허용 범위에 맞춰 시작일 기준 한 달 이내 구간으로 나눈다.
            LocalDate chunkEndDate = chunkStartDate.plusMonths(1).minusDays(1);
            if (chunkEndDate.isAfter(endDate)) {
                chunkEndDate = endDate;
            }

            BidListPage firstPage = fetchBidListPage(chunkStartDate, chunkEndDate, 1);
            addUniqueBids(uniqueBids, firstPage.bidList());

            // 첫 페이지의 totalCount로 전체 페이지 수를 계산해 나머지 페이지를 모두 조회한다.
            int totalPages = (firstPage.totalCount() + BID_LIST_PAGE_SIZE - 1) / BID_LIST_PAGE_SIZE;
            for (int pageNo = 2; pageNo <= totalPages; pageNo++) {
                BidListPage page = fetchBidListPage(chunkStartDate, chunkEndDate, pageNo);
                addUniqueBids(uniqueBids, page.bidList());
            }

            chunkStartDate = chunkEndDate.plusDays(1);
        }

        return new ArrayList<>(uniqueBids.values());
    }

    /**
     * 지정한 구간의 한 페이지를 조회하고 응답 상태와 공고 목록을 함께 해석한다.
     */
    private BidListPage fetchBidListPage(LocalDate startDate, LocalDate endDate, int pageNo) {
        return parseBidListPage(requestBidListPage(startDate, endDate, pageNo));
    }

    /**
     * 나라장터 응답의 resultCode를 검증하고 한 페이지의 공고와 전체 건수를 반환한다.
     */
    private BidListPage parseBidListPage(String responseBody) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<BidDto> bidList = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = root.path("response");
            JsonNode header = response.path("header");

            // 입력범위 초과 등의 오류는 별도 ResponseError 루트로 내려오므로 함께 확인한다.
            if (response.isMissingNode()) {
                header = root.path("nkoneps.com.response.ResponseError").path("header");
            }

            String resultCode = header.path("resultCode").asText();
            String resultMsg = header.path("resultMsg").asText();
            if (!"00".equals(resultCode)) {
                String errorCode = resultCode.isEmpty() ? "알 수 없는 코드" : resultCode;
                String errorMessage = resultMsg.isEmpty() ? "응답 메시지 없음" : resultMsg;
                throw new IllegalStateException(
                        "나라장터 입찰공고 조회에 실패했습니다. [" + errorCode + "] " + errorMessage);
            }

            JsonNode body = response.path("body");
            if (body.isMissingNode()) {
                throw new IllegalStateException("나라장터 입찰공고 응답에 body가 없습니다.");
            }

            int totalCount = body.path("totalCount").asInt();
            JsonNode items = body.path("items");
            // 일부 응답 형식에서 items.item으로 내려오는 경우도 처리한다.
            if (items.isObject() && items.has("item")) {
                items = items.path("item");
            }

            for (JsonNode item : items) {
                bidList.add(createBidDto(item));
            }

            return new BidListPage(bidList, totalCount);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("나라장터 API 응답을 JSON으로 변환할 수 없습니다.", e);
        }
    }

    /**
     * 공고번호를 기준으로 중복을 제거해 병합 결과에 추가한다.
     */
    private void addUniqueBids(Map<String, BidDto> uniqueBids, List<BidDto> bids) {
        for (BidDto bid : bids) {
            String bidNtceNo = getSafeValue(bid.getBidNtceNo());
            if (bidNtceNo.isEmpty()) {
                throw new IllegalStateException("나라장터 입찰공고 응답에 공고번호가 없는 항목이 있습니다.");
            }
            uniqueBids.putIfAbsent(bidNtceNo, bid);
        }
    }

    /**
     * 나라장터 공고 항목을 기존과 동일한 필드 구성의 BidDto로 변환한다.
     */
    private BidDto createBidDto(JsonNode item) {
        return new BidDto(
                item.path("bidNtceNo").asText(),
                item.path("bidNtceNm").asText(),
                item.path("ntceInsttNm").asText(),
                item.path("bidNtceDt").asText(),
                item.path("bidClseDt").asText(),
                item.path("asignBdgtAmt").asText(),
                item.path("sucsfbidMthdNm").asText(),
                item.path("bidNtceDtlUrl").asText(),
                item.path("sucsfbidMthdCd").asText(),
                item.path("techAbltEvlRt").asText(),
                item.path("bidPrceEvlRt").asText(),
                item.path("sucsfbidMthdAppStd").asText(),
                item.path("arsltCmptYn").asText(),
                item.path("pqEvalYn").asText(),
                item.path("tpEvalYn").asText(),
                item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
        );
    }

    /**
     * 대리님 요청 조건에 맞는 낙찰방법의 공고만 조회한다.
     */
    public List<BidDto> getTargetBidList() {
        return getBidDtoList().stream()
                // 협상에 의한 계약(낙030005)은 대상에서 제외한다.
                .filter(bid -> !"낙030005".equals(bid.getSucsfbidMthdCd()))
                // 소액수의견적: 코드가 낙030029이거나 낙찰방법명에 소액수의견적이 포함된 공고
                .filter(bid -> "낙030029".equals(bid.getSucsfbidMthdCd())
                        || bid.getSucsfbidMthdNm().contains("소액수의견적")
                        // 적격심사제: 코드가 낙030001이면서 낙찰방법명에 적격심사가 포함된 공고
                        || ("낙030001".equals(bid.getSucsfbidMthdCd())
                        && bid.getSucsfbidMthdNm().contains("적격심사")))
                .collect(Collectors.toList());
    }

    /**
     * 지정한 기간의 공고 중 소액수의견적 또는 적격심사제 대상 공고만 반환한다.
     */
    public List<BidDto> getTargetBidList(LocalDate startDate, LocalDate endDate) {
        // 기간 조회 결과에 기존과 동일한 대상 공고 조건을 적용한다.
        return getBidDtoList(startDate, endDate).stream()
                .filter(this::isTargetBid)
                .collect(Collectors.toList());
    }

    /**
     * 기존 대상 공고 필터와 같은 소액수의견적 및 적격심사제 조건을 판단한다.
     */
    private boolean isTargetBid(BidDto bid) {
        String sucsfbidMthdCd = getSafeValue(bid.getSucsfbidMthdCd());
        String sucsfbidMthdNm = getSafeValue(bid.getSucsfbidMthdNm());

        return !"낙030005".equals(sucsfbidMthdCd)
                && ("낙030029".equals(sucsfbidMthdCd)
                || sucsfbidMthdNm.contains("소액수의견적")
                || ("낙030001".equals(sucsfbidMthdCd)
                && sucsfbidMthdNm.contains("적격심사")));
    }

    /**
     * 오늘의 대상 공고에 참가조건 자동 판정을 적용한 결과를 반환한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList() {
        return getTargetBidQualificationList(DEFAULT_ALLOWED_LICENSE_CODES);
    }

    /**
     * 오늘의 대상 공고를 브라우저에서 전달한 허용 업종코드로 자동 판정한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList(Set<String> allowedLicenseCodes) {
        Set<String> normalizedCodes = normalizeAllowedLicenseCodes(allowedLicenseCodes);
        // 기존 대상 필터로 소액수의견적 및 적격심사제 공고만 먼저 조회한다.
        return getTargetBidList().stream()
                // 공고별 구조화 면허조건을 현재 허용 코드와 비교해 자동 판정한다.
                .map(BidDto::getBidNtceNo)
                .map(bidNtceNo -> getBidQualification(bidNtceNo, normalizedCodes))
                .collect(Collectors.toList());
    }

    /**
     * 지정한 기간의 대상 공고에 기존 참가조건 자동 판정을 적용해 반환한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList(LocalDate startDate, LocalDate endDate) {
        return getTargetBidQualificationList(startDate, endDate, DEFAULT_ALLOWED_LICENSE_CODES);
    }

    /**
     * 지정한 기간의 대상 공고를 브라우저에서 전달한 허용 업종코드로 자동 판정한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList(
            LocalDate startDate,
            LocalDate endDate,
            Set<String> allowedLicenseCodes
    ) {
        Set<String> normalizedCodes = normalizeAllowedLicenseCodes(allowedLicenseCodes);
        // 기간별 대상 공고마다 기존 통합조회 및 자동 판정 로직을 재사용한다.
        return getTargetBidList(startDate, endDate).stream()
                .map(BidDto::getBidNtceNo)
                .map(bidNtceNo -> getBidQualification(bidNtceNo, normalizedCodes))
                .collect(Collectors.toList());
    }

    /**
     * 한 페이지의 공고 목록과 해당 조회 구간의 전체 건수를 함께 보관한다.
     */
    private record BidListPage(List<BidDto> bidList, int totalCount) {
    }

    /** 면허 그룹 판정 결과와 추가 확인 사유를 함께 보관한다. */
    private record LicenseReviewResult(boolean satisfied, String reason) {
    }

    /** 공고 직접조회에서 변환한 기본정보와 첨부파일 목록을 함께 보관한다. */
    private record BidDetail(BidDto bidDto, List<BidAttachmentDto> attachments) {
    }
}
