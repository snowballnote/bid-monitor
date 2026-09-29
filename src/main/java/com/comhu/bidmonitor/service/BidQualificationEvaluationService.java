package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.classifier.BidAwardMethodCategory;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.classifier.BidAwardMethodResult;
import com.comhu.bidmonitor.classifier.BidAwardMethodStatus;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 출처와 무관하게 공고의 낙찰방법과 회사 참가조건을 같은 규칙으로 판정한다. */
@Service
public class BidQualificationEvaluationService {

    private static final String REQUIRED_LICENSE_CODE = "6146";
    private static final Set<String> REFERENCE_SITE_DOMAINS = Set.of("smpp.go.kr");

    private final BidAwardMethodClassifier bidAwardMethodClassifier;

    public BidQualificationEvaluationService(BidAwardMethodClassifier bidAwardMethodClassifier) {
        this.bidAwardMethodClassifier = bidAwardMethodClassifier;
    }

    /** 기존 DTO 필드를 유지하면서 낙찰방법 분류와 검토 결과를 한 번에 갱신한다. */
    public void evaluate(BidQualificationDto qualification, Set<String> allowedLicenseCodes) {
        if (qualification == null) {
            throw new IllegalArgumentException("판정할 입찰공고가 없습니다.");
        }
        applyAwardMethodClassification(qualification);
        applyReviewResult(qualification, allowedLicenseCodes == null ? Set.of() : allowedLicenseCodes);
    }

    /** 분석이 끝난 첨부 메타데이터에서 공고 단위 외부확인 여부와 사유를 계산한다. */
    public void evaluateExternalCheck(
            BidQualificationDto qualification,
            List<BidAttachmentDto> analyzedAttachments
    ) {
        if (qualification == null) {
            throw new IllegalArgumentException("판정할 입찰공고가 없습니다.");
        }
        qualification.setExternalSiteUrls(new ArrayList<>());
        List<BidAttachmentDto> analysisTargets = analyzedAttachments == null
                ? List.of()
                : analyzedAttachments;
        if (analysisTargets.isEmpty()) {
            setUnknownExternalCheckResult(qualification, "분석 가능한 PDF/HWPX/HWP 첨부파일이 없음");
            return;
        }

        List<BidAttachmentDto> detectedAttachments = analysisTargets.stream()
                .filter(attachment -> Boolean.TRUE.equals(attachment.getExternalReferenceDetected()))
                .toList();
        Set<String> externalUrls = detectedAttachments.stream()
                .filter(attachment -> attachment.getDetectedExternalUrls() != null)
                .flatMap(attachment -> attachment.getDetectedExternalUrls().stream())
                .filter(url -> !safeValue(url).isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> referenceUrls = externalUrls.stream()
                .filter(this::isReferenceSiteUrl)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> requiredUrls = externalUrls.stream()
                .filter(url -> !isReferenceSiteUrl(url))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean keywordSignalDetected = detectedAttachments.stream()
                .anyMatch(this::hasExternalReferenceKeywordSignal);

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
                .map(attachment -> safeValue(attachment.getFileName()).trim())
                .filter(fileName -> !fileName.isEmpty())
                .distinct()
                .toList();
        if (!failedFileNames.isEmpty()) {
            setUnknownExternalCheckResult(
                    qualification,
                    "첨부파일 분석 실패로 외부 확인 필요 여부를 판단할 수 없음: "
                            + String.join(", ", failedFileNames)
            );
            return;
        }

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

    private String createAttachmentExternalReason(BidAttachmentDto attachment) {
        String fileName = safeValue(attachment.getFileName()).trim();
        String analysisReason = safeValue(attachment.getAnalysisReason()).trim();
        if (fileName.isEmpty()) {
            return analysisReason;
        }
        return analysisReason.isEmpty() ? fileName : fileName + ": " + analysisReason;
    }

    private boolean hasExternalReferenceKeywordSignal(BidAttachmentDto attachment) {
        return safeValue(attachment.getAnalysisReason()).contains("탐지 키워드:");
    }

    private boolean isReferenceSiteUrl(String url) {
        try {
            String host = safeValue(URI.create(url).getHost()).toLowerCase(Locale.ROOT);
            return REFERENCE_SITE_DOMAINS.stream()
                    .anyMatch(domain -> domain.equals(host) || host.endsWith("." + domain));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void setUnknownExternalCheckResult(BidQualificationDto qualification, String reason) {
        qualification.setExternalCheckStatus("UNKNOWN");
        qualification.setExternalSiteCheckRequired(null);
        qualification.setExternalSiteUrls(new ArrayList<>());
        qualification.setExternalCheckReason(reason);
    }

    private void applyAwardMethodClassification(BidQualificationDto qualification) {
        BidAwardMethodResult result = bidAwardMethodClassifier.classify(qualification);
        qualification.setAwardMethodCategory(result.category().name());
        qualification.setAwardMethodStatus(result.status().name());
        qualification.setAwardMethodReason(result.reason());
        qualification.setAwardMethodSource(result.source().name());
    }

    private void applyReviewResult(BidQualificationDto qualification, Set<String> allowedLicenseCodes) {
        String sucsfbidMthdCd = safeValue(qualification.getSucsfbidMthdCd());
        String sucsfbidMthdNm = safeValue(qualification.getSucsfbidMthdNm());
        BidAwardMethodCategory awardMethodCategory = BidAwardMethodCategory.valueOf(
                safeValue(qualification.getAwardMethodCategory())
        );
        BidAwardMethodStatus awardMethodStatus = BidAwardMethodStatus.valueOf(
                safeValue(qualification.getAwardMethodStatus())
        );

        if ("낙030005".equals(sucsfbidMthdCd) || sucsfbidMthdNm.contains("협상")) {
            qualification.setReviewStatus("제외");
            qualification.setReviewReason("협상에 의한 계약으로 대상 제외");
            return;
        }

        boolean smallAmountEstimate = awardMethodCategory == BidAwardMethodCategory.SMALL_AMOUNT_ESTIMATE;
        boolean qualificationReview = awardMethodCategory == BidAwardMethodCategory.QUALIFICATION_REVIEW;
        if (!smallAmountEstimate && !qualificationReview) {
            if (awardMethodCategory == BidAwardMethodCategory.OTHER) {
                qualification.setReviewStatus("제외");
                qualification.setReviewReason("낙찰방법이 적격심사 또는 소액수의견적 대상이 아님");
            } else {
                qualification.setReviewStatus("추가확인필요");
                qualification.setReviewReason("낙찰방법이 검토대상 기준에 해당하는지 확인 필요");
            }
            return;
        }

        List<String> additionalCheckReasons = new ArrayList<>();
        String participationRegion = safeValue(qualification.getParticipationRegion());
        if (qualificationReview && awardMethodStatus == BidAwardMethodStatus.LIKELY) {
            additionalCheckReasons.add("첨부문서 근거의 적격심사 여부 확인 필요");
        }

        LicenseReviewResult licenseReviewResult = reviewLicenseGroups(
                qualification.getLicenseGroups(), allowedLicenseCodes
        );
        if (!licenseReviewResult.satisfied()) {
            additionalCheckReasons.add(licenseReviewResult.reason());
        }
        if (!"제한없음".equals(participationRegion)) {
            additionalCheckReasons.add(participationRegion.isEmpty()
                    ? "지역제한 조건 확인 필요"
                    : "지역제한 조건 확인 필요: " + participationRegion);
        }
        if ("Y".equals(safeValue(qualification.getArsltCmptYn()))) {
            additionalCheckReasons.add("실적경쟁 조건 확인 필요");
        }
        if ("Y".equals(safeValue(qualification.getPqEvalYn()))) {
            additionalCheckReasons.add("PQ심사 조건 확인 필요");
        }
        if ("Y".equals(safeValue(qualification.getTpEvalYn()))) {
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
                    .map(this::safeValue)
                    .anyMatch(REQUIRED_LICENSE_CODE::equals);

            hasRequiredLicense |= groupHasRequiredLicense;
            if (!groupHasRequiredLicense || requirements.isEmpty()) {
                continue;
            }

            LinkedHashSet<String> missingCodes = new LinkedHashSet<>();
            boolean groupHasUnknownLicenseCode = false;
            for (LicenseRequirement requirement : requirements) {
                String licenseCode = requirement == null ? "" : safeValue(requirement.getLicenseCode()).trim();
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
            return new LicenseReviewResult(false,
                    "추가 면허조건 확인 필요: " + String.join(", ", closestMissingCodes));
        }
        if (hasUnknownLicenseCode) {
            return new LicenseReviewResult(false, "면허조건 코드 확인 필요");
        }
        return new LicenseReviewResult(false, "허용 면허조건 확인 필요");
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private record LicenseReviewResult(boolean satisfied, String reason) {
    }
}
