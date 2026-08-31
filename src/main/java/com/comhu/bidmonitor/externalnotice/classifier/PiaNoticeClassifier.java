package com.comhu.bidmonitor.externalnotice.classifier;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 수집된 공지의 제목과 본문만으로 PIA 관련 여부를 판정한다.
 * 신청·접수처럼 범용적인 단어는 매칭 내역에는 포함하되 단독으로 관련 판정을 만들지 않는다.
 */
@Component
public class PiaNoticeClassifier {

    private static final List<String> CORE_KEYWORDS = List.of(
            "개인정보 영향평가",
            "영향평가",
            "PIA",
            "전문인력",
            "전문교육",
            "계속교육",
            "보수교육"
    );
    private static final List<String> SUPPORT_KEYWORDS = List.of(
            "자격",
            "자격증",
            "갱신",
            "유효기간",
            "신청",
            "접수",
            "마감"
    );
    private static final List<String> ALL_KEYWORDS = Stream.concat(
            CORE_KEYWORDS.stream(),
            SUPPORT_KEYWORDS.stream()
    ).toList();
    private static final Pattern PIA_PATTERN = Pattern.compile(
            "(?<![a-z0-9])p\\s*i\\s*a(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE
    );

    public PiaClassificationResult classify(CollectedNotice notice) {
        Objects.requireNonNull(notice, "판정할 공지는 null일 수 없습니다.");

        Set<String> titleMatches = findMatches(notice.getTitle());
        Set<String> bodyMatches = findMatches(notice.getBody());
        Set<String> allMatches = new LinkedHashSet<>(titleMatches);
        allMatches.addAll(bodyMatches);

        Set<String> titleCoreMatches = retainCoreKeywords(titleMatches);
        Set<String> bodyCoreMatches = retainCoreKeywords(bodyMatches);
        boolean piaRelated = !titleCoreMatches.isEmpty() || !bodyCoreMatches.isEmpty();

        return new PiaClassificationResult(
                piaRelated,
                List.copyOf(allMatches),
                createReason(titleCoreMatches, bodyCoreMatches, allMatches)
        );
    }

    private Set<String> findMatches(String text) {
        String original = text == null ? "" : text;
        String normalized = normalize(original);
        Set<String> matches = new LinkedHashSet<>();

        for (String keyword : ALL_KEYWORDS) {
            boolean matched = "PIA".equals(keyword)
                    ? PIA_PATTERN.matcher(original).find()
                    : normalized.contains(normalize(keyword));
            if (matched) {
                matches.add(keyword);
            }
        }
        return matches;
    }

    private Set<String> retainCoreKeywords(Set<String> matches) {
        Set<String> coreMatches = new LinkedHashSet<>(matches);
        coreMatches.retainAll(CORE_KEYWORDS);
        return coreMatches;
    }

    private String createReason(
            Set<String> titleCoreMatches,
            Set<String> bodyCoreMatches,
            Set<String> allMatches
    ) {
        if (!titleCoreMatches.isEmpty()) {
            return "제목에서 PIA 핵심 키워드 발견: " + String.join(", ", titleCoreMatches);
        }
        if (!bodyCoreMatches.isEmpty()) {
            return "본문에서 PIA 핵심 키워드 발견: " + String.join(", ", bodyCoreMatches);
        }
        if (!allMatches.isEmpty()) {
            return "PIA 핵심 키워드 없이 보조 키워드만 발견: " + String.join(", ", allMatches);
        }
        return "PIA 관련 키워드가 발견되지 않았습니다.";
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
