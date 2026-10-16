
package com.comhu.bidmonitor.bid.source.kogas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KogasOpenApiReaderTests {

    @Test
    void readsAllPages() {
        var client = mock(KogasOpenApiClient.class);
        var reader = new KogasOpenApiReader(
                client, new KogasOpenApiParser(), 2
        );

        when(client.fetchPage("20261014", "20261014", 1, 2))
                .thenReturn(xml(3, 1, 2, "A", "B"));

        when(client.fetchPage("20261014", "20261014", 2, 2))
                .thenReturn(xml(3, 2, 2, "C"));

        var notices = reader.fetchAll(
                LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 14)
        );

        assertEquals(
                Arrays.asList("A", "B", "C"),
                notices.stream()
                        .map(KogasOpenApiParser.KogasApiNotice::noticeCode)
                        .toList()
        );

        verify(client, times(2))
                .fetchPage(anyString(), anyString(), anyInt(), eq(2));
    }

    @Test
    void rejectsDuplicateNotices() {
        var client = mock(KogasOpenApiClient.class);
        var reader = new KogasOpenApiReader(
                client, new KogasOpenApiParser(), 2
        );

        when(client.fetchPage("20261014", "20261014", 1, 2))
                .thenReturn(xml(3, 1, 2, "A", "B"));

        when(client.fetchPage("20261014", "20261014", 2, 2))
                .thenReturn(xml(3, 2, 2, "B"));

        assertThrows(
                IllegalStateException.class,
                () -> reader.fetchAll(
                        LocalDate.of(2026, 10, 14),
                        LocalDate.of(2026, 10, 14)
                )
        );
    }

    @Test
    @EnabledIfSystemProperty(named = "kogas.live", matches = "true")
    void fetchesRealApi() {
        String apiKey = System.getenv("KOGAS_API_SERVICE_KEY");

        assertNotNull(apiKey, "KOGAS_API_SERVICE_KEY is missing");
        assertFalse(apiKey.isBlank(), "KOGAS API key is blank");

        var client = new KogasOpenApiClient(
                "https://apis.data.go.kr/B551210/bidInfoList2/getBidInfoList2",
                apiKey
        );

        var reader = new KogasOpenApiReader(
                client, new KogasOpenApiParser()
        );

        var notices = reader.fetchAll(
                LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 14)
        );

        System.out.println("KOGAS 조회 건수: " + notices.size());

        notices.stream().limit(5).forEach(notice ->
                System.out.println(
                        notice.noticeCode() + " | " + notice.noticeName()
                )
        );
    }

    private String xml(
            int totalCount,
            int pageNo,
            int numOfRows,
            String... noticeCodes
    ) {
        String items = Arrays.stream(noticeCodes)
                .map(code -> """
                        <item>
                            <NOTICE_CODE>%s</NOTICE_CODE>
                            <NOTICE_NAME>테스트 공고</NOTICE_NAME>
                        </item>
                        """.formatted(code))
                .collect(Collectors.joining());

        return """
                <response>
                    <header>
                        <resultCode>00</resultCode>
                    </header>
                    <body>
                        <items>%s</items>
                        <numOfRows>%d</numOfRows>
                        <pageNo>%d</pageNo>
                        <totalCount>%d</totalCount>
                    </body>
                </response>
                """.formatted(items, numOfRows, pageNo, totalCount);
    }
}