package com.comhu.bidmonitor.externalnotice.collector.pia;

import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollectionException;
import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollector;
import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import com.comhu.bidmonitor.externalnotice.domain.NoticeSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개인정보 포털 공지사항 첫 페이지와 각 상세페이지를 읽어 공통 공지 모델로 변환한다.
 * 중요 키워드 판정은 후속 단계의 별도 책임이므로 이 수집기는 게시판의 모든 공지를 반환한다.
 */
@Service
public class PiaNoticeCollector implements ExternalNoticeCollector {

    static final String BOARD_ID = "BBSMSTR_000000000001";
    static final String SITE_BASE_URL = "https://www.privacy.go.kr";
    private static final String DETAIL_PATH = "/front/bbs/bbsView.do?bbsNo=" + BOARD_ID;
    private static final Pattern NOTICE_ID_PATTERN = Pattern.compile("\\$bbs\\.view\\('([0-9]+)'");

    private final Function<URI, String> htmlLoader;

    /** 실제 실행에서는 Spring RestClient를 생성해 원격 HTML을 가져온다. */
    public PiaNoticeCollector() {
        RestClient restClient = RestClient.create();
        this.htmlLoader = uri -> restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }

    /** 고정 HTML을 사용하는 단위 테스트가 외부 네트워크 없이 파서를 검증할 수 있게 한다. */
    PiaNoticeCollector(Function<URI, String> htmlLoader) {
        this.htmlLoader = htmlLoader;
    }

    @Override
    public NoticeSource getSource() {
        return NoticeSource.PRIVACY_PORTAL;
    }

    @Override
    public List<CollectedNotice> collect() {
        List<PiaNoticeSummary> summaries = parseNoticeList(loadHtml(getSource().getListUrl()));
        List<CollectedNotice> notices = new ArrayList<>();

        for (PiaNoticeSummary summary : summaries) {
            notices.add(parseNoticeDetail(summary, loadHtml(summary.detailUrl())));
        }
        return notices;
    }

    /** 최근 1페이지의 행을 읽고 같은 bbscttNo가 반복되면 첫 항목만 보존한다. */
    List<PiaNoticeSummary> parseNoticeList(String html) {
        Document document = parseHtml(html, getSource().getListUrl(), "목록");
        Element listRoot = requireElement(
                document,
                ".contentBox.master_list",
                "PIA 공지 목록 영역(.contentBox.master_list)을 찾을 수 없습니다."
        );
        Element table = requireElement(
                listRoot,
                "table.oneTable tbody",
                "PIA 공지 목록 표(table.oneTable tbody)를 찾을 수 없습니다."
        );
        Elements rows = table.children();
        if (rows.isEmpty()) {
            throw new ExternalNoticeCollectionException("PIA 공지 목록 표에 게시글 행이 없습니다.");
        }

        Map<String, PiaNoticeSummary> uniqueSummaries = new LinkedHashMap<>();
        for (Element row : rows) {
            Element titleLink = requireElement(
                    row,
                    "td.oneColTitle > a[onclick]",
                    "PIA 공지 목록 행에서 제목 링크를 찾을 수 없습니다."
            );
            String sourceNoticeId = extractSourceNoticeId(titleLink.attr("onclick"));
            String title = requireText(
                    requireElement(row, "td.oneColTitle .qmaText", "PIA 공지 제목 요소를 찾을 수 없습니다."),
                    "PIA 공지 제목이 비어 있습니다."
            );
            Elements cells = row.children();
            if (cells.size() < 4) {
                throw new ExternalNoticeCollectionException("PIA 공지 목록 행의 등록일 열을 찾을 수 없습니다.");
            }
            LocalDate publishedDate = parseDate(cells.get(3).text(), "목록 등록일");
            String detailUrl = createDetailUrl(sourceNoticeId);

            uniqueSummaries.putIfAbsent(
                    sourceNoticeId,
                    new PiaNoticeSummary(sourceNoticeId, title, publishedDate, detailUrl)
            );
        }
        return new ArrayList<>(uniqueSummaries.values());
    }

    /** 상세페이지의 값을 최종 기준으로 사용하고 첨부가 없으면 빈 목록으로 반환한다. */
    CollectedNotice parseNoticeDetail(PiaNoticeSummary summary, String html) {
        Document document = parseHtml(html, summary.detailUrl(), "상세");
        Element viewRoot = requireElement(
                document,
                ".contentBox.master_view .oneView",
                "PIA 공지 상세 영역(.contentBox.master_view .oneView)을 찾을 수 없습니다."
        );
        Element noticeIdInput = requireElement(
                document,
                "form#viewForm input#bbscttNo",
                "PIA 공지 상세페이지에서 bbscttNo를 찾을 수 없습니다."
        );
        String detailNoticeId = noticeIdInput.val().trim();
        if (!summary.sourceNoticeId().equals(detailNoticeId)) {
            throw new ExternalNoticeCollectionException(
                    "PIA 공지 상세페이지의 bbscttNo가 요청값과 다릅니다: " + detailNoticeId
            );
        }

        String title = requireOwnText(
                requireElement(viewRoot, ".oneView_title", "PIA 공지 상세 제목을 찾을 수 없습니다."),
                "PIA 공지 상세 제목이 비어 있습니다."
        );
        LocalDate publishedDate = findPublishedDate(viewRoot);
        String body = requireText(
                requireElement(viewRoot, ".oneView_body .viewBox", "PIA 공지 본문을 찾을 수 없습니다."),
                "PIA 공지 본문이 비어 있습니다."
        );

        return CollectedNotice.builder()
                .source(getSource())
                .externalId(createExternalId(summary.sourceNoticeId()))
                .sourceNoticeId(summary.sourceNoticeId())
                .title(title)
                .publishedDate(publishedDate)
                .detailUrl(summary.detailUrl())
                .body(body)
                .attachments(parseAttachments(viewRoot))
                .build();
    }

    private String loadHtml(String url) {
        try {
            String html = htmlLoader.apply(URI.create(url));
            if (html == null || html.isBlank()) {
                throw new ExternalNoticeCollectionException("PIA 공지 HTML 응답이 비어 있습니다: " + url);
            }
            return html;
        } catch (ExternalNoticeCollectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExternalNoticeCollectionException("PIA 공지 페이지 호출에 실패했습니다: " + url, e);
        }
    }

    private Document parseHtml(String html, String baseUrl, String pageType) {
        if (html == null || html.isBlank()) {
            throw new ExternalNoticeCollectionException("PIA 공지 " + pageType + " HTML이 비어 있습니다.");
        }
        return Jsoup.parse(html, baseUrl);
    }

    private String extractSourceNoticeId(String onclick) {
        Matcher matcher = NOTICE_ID_PATTERN.matcher(onclick);
        if (!matcher.find()) {
            throw new ExternalNoticeCollectionException("PIA 공지 제목 링크에서 bbscttNo를 추출할 수 없습니다.");
        }
        return matcher.group(1);
    }

    private LocalDate findPublishedDate(Element viewRoot) {
        for (Element info : viewRoot.select(".oneView_info > .oneView_infoTitle")) {
            if ("등록일".equals(info.ownText().trim())) {
                Element value = info.selectFirst(".oneView_infoText");
                if (value == null) {
                    break;
                }
                return parseDate(value.text(), "상세 등록일");
            }
        }
        throw new ExternalNoticeCollectionException("PIA 공지 상세페이지에서 등록일을 찾을 수 없습니다.");
    }

    private List<NoticeAttachment> parseAttachments(Element viewRoot) {
        List<NoticeAttachment> attachments = new ArrayList<>();
        for (Element link : viewRoot.select(".oneView_fileList > li > a[href]")) {
            String fileName = link.ownText().trim();
            String fileUrl = link.absUrl("href").trim();
            if (fileName.isEmpty() || fileUrl.isEmpty()) {
                throw new ExternalNoticeCollectionException("PIA 공지 첨부파일명 또는 다운로드 URL이 비어 있습니다.");
            }
            attachments.add(NoticeAttachment.builder()
                    .fileName(fileName)
                    .fileUrl(fileUrl)
                    .build());
        }
        return attachments;
    }

    private LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ExternalNoticeCollectionException(
                    "PIA 공지 " + fieldName + " 형식이 yyyy-MM-dd가 아닙니다: " + value,
                    e
            );
        }
    }

    private Element requireElement(Element root, String selector, String message) {
        Element element = root.selectFirst(selector);
        if (element == null) {
            throw new ExternalNoticeCollectionException(message);
        }
        return element;
    }

    private String requireText(Element element, String message) {
        String text = element.text().trim();
        if (text.isEmpty()) {
            throw new ExternalNoticeCollectionException(message);
        }
        return text;
    }

    private String requireOwnText(Element element, String message) {
        String text = element.ownText().trim();
        if (text.isEmpty()) {
            throw new ExternalNoticeCollectionException(message);
        }
        return text;
    }

    private String createExternalId(String sourceNoticeId) {
        return getSource().getCode() + ":" + BOARD_ID + ":" + sourceNoticeId;
    }

    private String createDetailUrl(String sourceNoticeId) {
        return SITE_BASE_URL + DETAIL_PATH + "&bbscttNo=" + sourceNoticeId;
    }

    record PiaNoticeSummary(
            String sourceNoticeId,
            String title,
            LocalDate publishedDate,
            String detailUrl
    ) {
    }
}
