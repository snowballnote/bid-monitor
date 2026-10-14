package com.comhu.bidmonitor.bid.license;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes explicit industry qualifications without applying company eligibility policy.
 * Generic numeric extraction is intentionally limited to fields whose source contract identifies
 * them as industry, license, or qualification data.
 */
public final class LicenseNormalizer {

    public static final String INFORMATION_SYSTEM_SUPERVISION_NAME = "정보시스템 감리법인";
    public static final String COMPUTER_SERVICE_BUSINESS_NAME = "소프트웨어사업자(컴퓨터관련서비스사업)";

    private static final Pattern CANONICAL_CODE = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");
    private static final List<NormalizedLicense> VERIFIED_LICENSES = List.of(
            new NormalizedLicense("6146", INFORMATION_SYSTEM_SUPERVISION_NAME),
            new NormalizedLicense("1468", COMPUTER_SERVICE_BUSINESS_NAME)
    );

    private LicenseNormalizer() {
    }

    /** Normalizes a value that is already known to be an explicit industry code. */
    public static Optional<NormalizedLicense> normalizeExplicitCode(String value) {
        if (value == null || !value.trim().matches("\\d{4}")) {
            return Optional.empty();
        }
        String code = value.trim();
        return Optional.of(new NormalizedLicense(code, officialName(code)));
    }

    /** Maps only exact, previously verified official qualification names. */
    public static Optional<NormalizedLicense> normalizeVerifiedName(String value) {
        String normalized = withoutWhitespace(value);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return VERIFIED_LICENSES.stream()
                .filter(license -> withoutWhitespace(license.officialName()).equals(normalized))
                .findFirst()
                .map(license -> license);
    }

    /**
     * Extracts canonical codes and verified names from a structured qualification field.
     * Callers must not use this method for titles, descriptions, or other general text.
     */
    public static List<NormalizedLicense> normalizeStructuredQualification(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, NormalizedLicense> result = new LinkedHashMap<>();
        Matcher matcher = CANONICAL_CODE.matcher(value);
        while (matcher.find()) {
            String code = matcher.group(1);
            result.putIfAbsent(code, new NormalizedLicense(code, officialName(code)));
        }

        String normalized = withoutWhitespace(value);
        VERIFIED_LICENSES.forEach(license -> {
            if (normalized.contains(withoutWhitespace(license.officialName()))) {
                result.putIfAbsent(license.code(), license);
            }
        });
        return List.copyOf(new ArrayList<>(result.values()));
    }

    public static String officialName(String code) {
        return VERIFIED_LICENSES.stream()
                .filter(license -> license.code().equals(code))
                .map(NormalizedLicense::officialName)
                .findFirst()
                .orElse("");
    }

    private static String withoutWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    public record NormalizedLicense(String code, String officialName) {
    }
}
