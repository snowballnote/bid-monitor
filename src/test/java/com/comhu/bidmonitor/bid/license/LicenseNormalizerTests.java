package com.comhu.bidmonitor.bid.license;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseNormalizerTests {

    @Test
    void preservesAnyExplicitCanonicalIndustryCode() {
        assertEquals("6146", LicenseNormalizer.normalizeExplicitCode("6146").orElseThrow().code());
        assertEquals("6525", LicenseNormalizer.normalizeExplicitCode(" 6525 ").orElseThrow().code());
        assertEquals("9999", LicenseNormalizer.normalizeExplicitCode("9999").orElseThrow().code());
    }

    @Test
    void mapsOnlyVerifiedOfficialQualificationNames() {
        assertEquals("6146", LicenseNormalizer.normalizeVerifiedName("정보시스템 감리법인")
                .orElseThrow().code());
        assertEquals("1468", LicenseNormalizer.normalizeVerifiedName(
                "소프트웨어사업자(컴퓨터관련서비스사업)").orElseThrow().code());
        assertTrue(LicenseNormalizer.normalizeVerifiedName("알 수 없는 전문면허").isEmpty());
    }

    @Test
    void extractsCodesOnlyThroughTheStructuredQualificationApi() {
        assertEquals(List.of("6525", "9999"), LicenseNormalizer.normalizeStructuredQualification(
                "업종코드: 6525, 면허코드: 9999"
        ).stream().map(LicenseNormalizer.NormalizedLicense::code).toList());

        assertTrue(LicenseNormalizer.normalizeExplicitCode("2026년 사업").isEmpty());
        assertTrue(LicenseNormalizer.normalizeVerifiedName("2026년 사업").isEmpty());
    }
}
