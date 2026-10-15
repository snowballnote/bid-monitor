
package com.comhu.bidmonitor.bid.source.kogas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KogasOpenApiParserTests {

    private final KogasOpenApiParser parser = new KogasOpenApiParser();

    @Test
    void parsesSuccessfulResponse() {
        String xml = """
                <response>
                    <header>
                        <resultCode>00</resultCode>
                        <resultMsg>NORMAL SERVICE.</resultMsg>
                    </header>
                    <body>
                        <items>
                            <item>
                                <NOTICE_CODE>2026101422</NOTICE_CODE>
                                <NOTICE_NAME>제주LNG본부 정비동 남자샤워실 보수공사</NOTICE_NAME>
                                <BID_TYPE_NAME>전자입찰</BID_TYPE_NAME>
                                <WORK_TYPE_NAME>공사</WORK_TYPE_NAME>
                                <CONT_METHOD_NAME>지명경쟁</CONT_METHOD_NAME>
                                <NOTICE_DT>2026-10-14</NOTICE_DT>
                                <END_DT>2026-10-21 10:00</END_DT>
                                <OPEN_DT>2026-10-21 10:10</OPEN_DT>
                                <CANCEL_YN/>
                            </item>
                        </items>
                        <numOfRows>1</numOfRows>
                        <pageNo>10</pageNo>
                        <totalCount>834</totalCount>
                    </body>
                </response>
                """;

        var page = parser.parse(xml);

        assertEquals(834, page.totalCount());
        assertEquals(10, page.pageNo());
        assertEquals(1, page.numOfRows());
        assertEquals(1, page.notices().size());

        var notice = page.notices().getFirst();

        assertEquals("2026101422", notice.noticeCode());
        assertEquals(
                "제주LNG본부 정비동 남자샤워실 보수공사",
                notice.noticeName()
        );
        assertEquals("2026-10-14", notice.noticeDate());
        assertEquals("공사", notice.workTypeName());
        assertEquals("", notice.cancelYn());
    }

    @Test
    void rejectsApiErrorResponse() {
        String xml = """
                <response>
                    <header>
                        <resultCode>10</resultCode>
                        <resultMsg>INVALID_REQUEST_PARAMETER_ERROR</resultMsg>
                    </header>
                </response>
                """;

        assertThrows(
                IllegalStateException.class,
                () -> parser.parse(xml)
        );
    }

    @Test
    void parsesEmptyResult() {
        String xml = """
                <response>
                    <header>
                        <resultCode>00</resultCode>
                    </header>
                    <body>
                        <items/>
                        <numOfRows>10</numOfRows>
                        <pageNo>1</pageNo>
                        <totalCount>0</totalCount>
                    </body>
                </response>
                """;

        var page = parser.parse(xml);

        assertEquals(0, page.totalCount());
        assertTrue(page.notices().isEmpty());
    }
}