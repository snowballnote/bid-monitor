package com.comhu.bidmonitor.externalnotice.fingerprint;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 공지의 변경 대상 필드만 안정적인 문자열로 구성하고 SHA-256 fingerprint를 생성한다.
 * 조회 URL이나 외부 식별자는 제외해 내용이 같은 공지를 변경된 것으로 오인하지 않게 한다.
 */
@Component
public class NoticeFingerprintGenerator {

    private static final Pattern CONSECUTIVE_WHITESPACE = Pattern.compile(
            "\\s+",
            Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Comparator<NormalizedAttachment> ATTACHMENT_COMPARATOR = Comparator
            .comparing(NormalizedAttachment::fileName)
            .thenComparing(NormalizedAttachment::fileUrl);

    public String generate(CollectedNotice notice) {
        Objects.requireNonNull(notice, "fingerprint를 생성할 공지는 null일 수 없습니다.");

        String canonicalValue = createCanonicalValue(notice);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 Java 표준 구현이 반드시 제공하므로 실행 환경 이상을 명확히 드러낸다.
            throw new IllegalStateException("SHA-256 fingerprint 생성기를 사용할 수 없습니다.", e);
        }
    }

    private String createCanonicalValue(CollectedNotice notice) {
        StringBuilder value = new StringBuilder();
        appendValue(value, normalize(notice.getTitle()));
        appendValue(value, notice.getPublishedDate() == null ? "" : notice.getPublishedDate().toString());
        appendValue(value, normalize(notice.getBody()));

        List<NormalizedAttachment> attachments = normalizeAttachments(notice.getAttachments());
        appendValue(value, Integer.toString(attachments.size()));
        for (NormalizedAttachment attachment : attachments) {
            appendValue(value, attachment.fileName());
            appendValue(value, attachment.fileUrl());
        }
        return value.toString();
    }

    private List<NormalizedAttachment> normalizeAttachments(List<NoticeAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        List<NormalizedAttachment> normalizedAttachments = new ArrayList<>();
        for (NoticeAttachment attachment : attachments) {
            normalizedAttachments.add(attachment == null
                    ? new NormalizedAttachment("", "")
                    : new NormalizedAttachment(
                            normalize(attachment.getFileName()),
                            normalize(attachment.getFileUrl())
                    ));
        }
        normalizedAttachments.sort(ATTACHMENT_COMPARATOR);
        return normalizedAttachments;
    }

    /** 값 길이를 함께 기록해 서로 다른 필드 조합이 같은 결합 문자열을 만드는 것을 방지한다. */
    private void appendValue(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return CONSECUTIVE_WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    private record NormalizedAttachment(String fileName, String fileUrl) {
    }
}
