package com.comhu.bidmonitor.notification.mail;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 외부 중요공지와 변경 유형을 안전한 plain-text 메일 제목·본문으로 변환한다. */
@Component
public class ExternalNoticeEmailFactory {

    private static final int SUBJECT_TITLE_MAX_CODE_POINTS = 80;

    public EmailMessage create(NotificationDelivery delivery, ExternalNotice notice) {
        String changeLabel = delivery.getChangeType() == NoticeChangeType.NEW ? "신규" : "변경";
        String description = delivery.getChangeType() == NoticeChangeType.NEW
                ? "PIA 관련 중요공지가 새로 등록되었습니다."
                : "PIA 관련 중요공지가 변경되었습니다.";
        String title = cleanText(notice.getTitle());
        String subjectTitle = truncate(title, SUBJECT_TITLE_MAX_CODE_POINTS);
        String subject = "[Biz Assist] PIA 중요공지 " + changeLabel
                + (subjectTitle.isBlank() ? "" : " - " + subjectTitle);

        StringBuilder body = new StringBuilder()
                .append(description).append("\n\n")
                .append("구분:\n").append(changeLabel).append("\n\n")
                .append("제목:\n").append(valueOrFallback(title, "제목 없음")).append("\n\n")
                .append("게시일:\n").append(formatPublishedDate(notice)).append("\n\n")
                .append("키워드:\n").append(formatKeywords(notice.getMatchedKeywords())).append("\n\n")
                .append("출처:\n").append(valueOrFallback(cleanText(notice.getSourceCode()), "확인 불가"));

        validHttpUrl(notice.getDetailUrl()).ifPresent(url -> body
                .append("\n\n원문 보기:\n")
                .append(url));
        body.append("\n\nBiz Assist");
        return new EmailMessage(subject, body.toString());
    }

    private String formatPublishedDate(ExternalNotice notice) {
        return notice.getPublishedDate() == null
                ? "확인 불가"
                : notice.getPublishedDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private String formatKeywords(List<String> keywords) {
        if (keywords == null) {
            return "없음";
        }
        String joined = keywords.stream()
                .map(this::cleanText)
                .filter(keyword -> !keyword.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return valueOrFallback(joined, "없음");
    }

    private java.util.Optional<String> validHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return java.util.Optional.of(uri.toASCIIString());
            }
        } catch (IllegalArgumentException ignored) {
            // 잘못된 URL은 메일에서 조용히 제외한다.
        }
        return java.util.Optional.empty();
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return Jsoup.parse(value).text()
                .replaceAll("[\\r\\n\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex).stripTrailing() + "…";
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
