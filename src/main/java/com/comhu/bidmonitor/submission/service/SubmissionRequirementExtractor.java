package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.domain.RequirementSourceType;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort.PmsRfpItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** RFP 원문에서 명시적인 제출서류 행을 우선 추출하고 기존 G2B 결과를 같은 도메인 모델로 변환한다. */
@Component
public class SubmissionRequirementExtractor {

    private static final List<String> SUBMISSION_SIGNALS = List.of(
            "제출", "구비서류", "제출서류", "첨부서류", "증빙서류"
    );
    private static final Map<String, RequirementCategory> DOCUMENT_TYPES = createDocumentTypes();

    public List<SubmissionDocumentRequirement> fromRfpItems(
            Long submissionCaseId,
            List<PmsRfpItem> items,
            Instant createdAt
    ) {
        Map<String, SubmissionDocumentRequirement> requirements = new LinkedHashMap<>();
        for (PmsRfpItem item : items == null ? List.<PmsRfpItem>of() : items) {
            String category = safe(item.category());
            String content = safe(item.content());
            boolean categoryIsSubmissionRelated = containsAny(category, SUBMISSION_SIGNALS);

            for (String rawLine : content.split("\\R")) {
                String line = normalizeLine(rawLine);
                if (line.isBlank() || (!categoryIsSubmissionRelated && !containsAny(line, SUBMISSION_SIGNALS))) {
                    continue;
                }
                String documentName = findCanonicalDocumentType(line);
                if (documentName == null) {
                    if (!looksLikeDocument(line)) {
                        continue;
                    }
                    documentName = abbreviate(line, 300);
                }
                String dedupKey = normalizeForMatch(documentName);
                requirements.putIfAbsent(dedupKey, SubmissionDocumentRequirement.builder()
                        .submissionCaseId(submissionCaseId)
                        .category(classify(documentName))
                        .documentName(documentName)
                        .required(!isConditional(line))
                        .evidenceText(abbreviate(line, 4000))
                        .sourceType(RequirementSourceType.PMS_RFP_ITEM)
                        .sourceReference(Long.toString(item.id()))
                        .createdAt(createdAt)
                        .build());
            }
        }
        return List.copyOf(requirements.values());
    }

    public List<SubmissionDocumentRequirement> fromG2bDocuments(
            Long submissionCaseId,
            String bidNoticeNo,
            List<String> documents,
            Instant createdAt
    ) {
        Map<String, SubmissionDocumentRequirement> requirements = new LinkedHashMap<>();
        for (String raw : documents == null ? List.<String>of() : documents) {
            String documentName = normalizeLine(raw);
            if (documentName.isBlank()) {
                continue;
            }
            String canonical = findCanonicalDocumentType(documentName);
            String name = canonical == null ? abbreviate(documentName, 300) : canonical;
            requirements.putIfAbsent(normalizeForMatch(name), SubmissionDocumentRequirement.builder()
                    .submissionCaseId(submissionCaseId)
                    .category(classify(name))
                    .documentName(name)
                    .required(!isConditional(documentName))
                    .evidenceText(abbreviate(documentName, 4000))
                    .sourceType(RequirementSourceType.G2B_DOCUMENT_ANALYSIS)
                    .sourceReference(bidNoticeNo)
                    .createdAt(createdAt)
                    .build());
        }
        return List.copyOf(requirements.values());
    }

    List<String> searchKeywords(String documentName) {
        List<String> keywords = new ArrayList<>();
        String canonical = findCanonicalDocumentType(documentName);
        if (canonical != null) {
            keywords.add(canonical);
        }
        for (String token : safe(documentName).split("[^0-9A-Za-z가-힣]+")) {
            if (token.length() >= 2 && !List.of("제출", "서류", "각각", "사본", "원본").contains(token)) {
                keywords.add(token);
            }
        }
        return keywords.stream().distinct().limit(6).toList();
    }

    String normalizeForMatch(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]", "");
    }

    private String findCanonicalDocumentType(String value) {
        String normalized = normalizeForMatch(value);
        return DOCUMENT_TYPES.keySet().stream()
                .filter(type -> normalized.contains(normalizeForMatch(type)))
                .findFirst()
                .orElse(null);
    }

    private RequirementCategory classify(String documentName) {
        String canonical = findCanonicalDocumentType(documentName);
        if (canonical != null) {
            return DOCUMENT_TYPES.get(canonical);
        }
        String normalized = normalizeForMatch(documentName);
        if (containsAny(normalized, List.of("실적", "계약", "납품"))) return RequirementCategory.PERFORMANCE;
        if (containsAny(normalized, List.of("인력", "경력", "재직", "학력"))) return RequirementCategory.PERSONNEL;
        if (containsAny(normalized, List.of("보안", "서약", "비밀"))) return RequirementCategory.SECURITY;
        if (containsAny(normalized, List.of("재무", "신용", "결산"))) return RequirementCategory.FINANCIAL;
        return RequirementCategory.OTHER;
    }

    private boolean looksLikeDocument(String value) {
        return containsAny(value, List.of(
                "증명서", "확인서", "등록증", "신고서", "신청서", "서약서", "제안서", "계획서", "명세서", "서류"
        ));
    }

    private boolean isConditional(String value) {
        return containsAny(value, List.of("해당 시", "해당시", "필요 시", "필요시", "해당하는 경우", "선택"));
    }

    private boolean containsAny(String value, List<String> keywords) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        return keywords.stream().map(keyword -> keyword.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    private String normalizeLine(String value) {
        return safe(value).replaceAll("^[\\s\\-–—·•※*○●□■①-⑳0-9.)\\]]+", "").trim();
    }

    private String abbreviate(String value, int maximumLength) {
        String normalized = safe(value).trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, RequirementCategory> createDocumentTypes() {
        Map<String, RequirementCategory> types = new LinkedHashMap<>();
        types.put("법인등기사항전부증명서", RequirementCategory.COMPANY_GENERAL);
        types.put("직접생산확인증명서", RequirementCategory.CERTIFICATION_LICENSE);
        types.put("사업자등록증", RequirementCategory.COMPANY_GENERAL);
        types.put("법인인감증명서", RequirementCategory.COMPANY_GENERAL);
        types.put("사용인감계", RequirementCategory.COMPANY_GENERAL);
        types.put("국세완납증명서", RequirementCategory.COMPANY_GENERAL);
        types.put("지방세완납증명서", RequirementCategory.COMPANY_GENERAL);
        types.put("중소기업확인서", RequirementCategory.CERTIFICATION_LICENSE);
        types.put("실적증명서", RequirementCategory.PERFORMANCE);
        types.put("재직증명서", RequirementCategory.PERSONNEL);
        types.put("경력증명서", RequirementCategory.PERSONNEL);
        types.put("자격증", RequirementCategory.CERTIFICATION_LICENSE);
        types.put("학위증명서", RequirementCategory.PERSONNEL);
        types.put("보안서약서", RequirementCategory.SECURITY);
        types.put("신용평가등급확인서", RequirementCategory.FINANCIAL);
        return Map.copyOf(types);
    }
}
