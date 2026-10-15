
package com.comhu.bidmonitor.bid.source.kogas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class KogasOpenApiReader {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    private final KogasOpenApiClient client;
    private final KogasOpenApiParser parser;
    private final int pageSize;

    @Autowired
    public KogasOpenApiReader(
            KogasOpenApiClient client,
            KogasOpenApiParser parser
    ) {
        this(client, parser, PAGE_SIZE);
    }

    KogasOpenApiReader(
            KogasOpenApiClient client,
            KogasOpenApiParser parser,
            int pageSize
    ) {
        this.client = Objects.requireNonNull(client);
        this.parser = Objects.requireNonNull(parser);

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Invalid page size");
        }

        this.pageSize = pageSize;
    }

    public List<KogasOpenApiParser.KogasApiNotice> fetchAll(
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (startDate == null || endDate == null
                || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Invalid date range");
        }

        String start = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);

        var firstPage = fetch(start, end, 1);

        int totalCount = firstPage.totalCount();
        int totalPages = Math.max(
                1,
                (int) (((long) totalCount + pageSize - 1) / pageSize)
        );

        // 예상보다 많은 API 호출을 방지한다.
        if (totalPages > MAX_PAGES) {
            throw new IllegalStateException("KOGAS page limit exceeded");
        }

        Map<String, KogasOpenApiParser.KogasApiNotice> notices =
                new LinkedHashMap<>();

        for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
            var page = pageNo == 1
                    ? firstPage
                    : fetch(start, end, pageNo);

            if (page.pageNo() != pageNo
                    || page.numOfRows() != pageSize
                    || page.totalCount() != totalCount) {
                throw new IllegalStateException(
                        "KOGAS pagination metadata changed"
                );
            }

            int expectedCount = Math.min(
                    pageSize,
                    totalCount - (pageNo - 1) * pageSize
            );

            if (page.notices().size() != expectedCount) {
                throw new IllegalStateException(
                        "KOGAS page contains unexpected number of notices"
                );
            }

            for (var notice : page.notices()) {
                // 페이지 간 중복으로 공고가 누락되는 상황을 감지한다.
                if (notices.putIfAbsent(notice.noticeCode(), notice) != null) {
                    throw new IllegalStateException(
                            "Duplicate KOGAS notice code"
                    );
                }
            }
        }

        return List.copyOf(notices.values());
    }

    private KogasOpenApiParser.KogasApiPage fetch(
            String start,
            String end,
            int pageNo
    ) {
        String xml = client.fetchPage(start, end, pageNo, pageSize);
        return parser.parse(xml);
    }
}