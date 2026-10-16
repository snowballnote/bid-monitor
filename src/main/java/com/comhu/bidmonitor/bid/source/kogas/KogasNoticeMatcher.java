
package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.util.List;
import java.util.Objects;

public final class KogasNoticeMatcher {

    private KogasNoticeMatcher() {
    }

    public static List<BidQualificationDto> findPotentialMatches(
            KogasOpenApiParser.KogasApiNotice apiNotice,
            List<BidQualificationDto> htmlNotices
    ) {
        Objects.requireNonNull(apiNotice);
        Objects.requireNonNull(htmlNotices);

        String noticeCode = apiNotice.noticeCode();

        if (noticeCode == null || noticeCode.isBlank()) {
            return List.of();
        }

        return htmlNotices.stream()
                .filter(html -> isPotentialMatch(noticeCode, html))
                .toList();
    }

    private static boolean isPotentialMatch(
            String noticeCode,
            BidQualificationDto html
    ) {
        if (html == null || !"KOGAS".equals(html.getSourceCode())) {
            return false;
        }

        String sourceNoticeId = html.getSourceNoticeId();

        if (sourceNoticeId == null
                || !sourceNoticeId.startsWith(noticeCode + ":")
                || sourceNoticeId.length() <= noticeCode.length() + 1) {
            return false;
        }

        // 같은 공고번호라도 입찰코드와 차수가 다를 수 있으므로 병합하지 않는다.
        return noticeCode.equals(html.getBidNtceNo());
    }
}