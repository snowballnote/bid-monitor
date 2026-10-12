package com.comhu.bidmonitor.classifier;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class BidRelevanceClassifier {

    private static final List<String> IRRELEVANT_PHRASES = List.of(
            "건설사업관리",
            "폐기물처리",
            "지하안전평가"
    );
    private static final List<String> NON_IT_SUPERVISION_CONTEXTS = List.of(
            "건설공사",
            "건설",
            "건축",
            "전기",
            "소방",
            "정보통신공사",
            "토목",
            "시설공사",
            "시설물"
    );
    private static final List<String> CLEAR_IT_CONTEXTS = List.of(
            "정보시스템",
            "정보화",
            "전산",
            "소프트웨어",
            "ict",
            "플랫폼",
            "클라우드",
            "데이터",
            "디지털"
    );
    private static final List<String> IT_SERVICE_ACTIONS = List.of(
            "구축",
            "운영",
            "개발",
            "유지관리"
    );
    private static final Pattern PIA = Pattern.compile("(^|[^a-z])pia([^a-z]|$)");
    private static final Pattern IT = Pattern.compile("(^|[^a-z])it([^a-z]|$)");

    public BidRelevance classify(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            return BidRelevance.UNKNOWN;
        }

        if (IRRELEVANT_PHRASES.stream().anyMatch(normalized::contains)) {
            return BidRelevance.IRRELEVANT;
        }
        if (isClearlyIrrelevantWork(normalized)) {
            return BidRelevance.IRRELEVANT;
        }
        if (normalized.contains("감리")
                && NON_IT_SUPERVISION_CONTEXTS.stream().anyMatch(normalized::contains)) {
            return BidRelevance.IRRELEVANT;
        }

        if (normalized.contains("개인정보영향평가") || isPrivacyImpactAssessment(title)) {
            return BidRelevance.RELEVANT;
        }

        boolean hasClearItContext = CLEAR_IT_CONTEXTS.stream().anyMatch(normalized::contains)
                || hasStandaloneIt(title);
        boolean hasSystemContext = normalized.contains("시스템");
        boolean hasItServiceAction = IT_SERVICE_ACTIONS.stream().anyMatch(normalized::contains);

        if (normalized.contains("감리") && (hasClearItContext || hasSystemContext)) {
            return BidRelevance.RELEVANT;
        }
        if (hasItServiceAction && (hasClearItContext || hasSystemContext)) {
            return BidRelevance.RELEVANT;
        }

        return BidRelevance.UNKNOWN;
    }

    private static boolean isClearlyIrrelevantWork(String normalized) {
        boolean constructionWasteTreatment = normalized.contains("건설폐기물")
                && normalized.contains("처리");
        boolean asbestosWorkSupervision = normalized.contains("석면")
                && normalized.contains("감리")
                && (normalized.contains("해체") || normalized.contains("제거"));
        boolean trafficSafetyFacilityReview = normalized.contains("교통안전시설")
                && normalized.contains("기술검토");
        return constructionWasteTreatment || asbestosWorkSupervision || trafficSafetyFacilityReview;
    }

    private static boolean isPrivacyImpactAssessment(String title) {
        String lowerCaseTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return lowerCaseTitle.replaceAll("\\s+", "").contains("개인정보")
                && PIA.matcher(lowerCaseTitle).find();
    }

    private static boolean hasStandaloneIt(String title) {
        return IT.matcher(title == null ? "" : title.toLowerCase(Locale.ROOT)).find();
    }

    private static String normalize(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
