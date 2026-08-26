package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceHwpxAttachmentAnalysisTests {

    private HttpServer httpServer;

    @AfterEach
    void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void extractsHwpxTextAndExcludesG2bUrl() throws Exception {
        byte[] hwpxBytes = createHwpx(
                "기관 홈페이지 참조 https://example.org/notice "
                        + "https://www.g2b.go.kr/notice 입찰서는 직접 제출"
        );
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/notice.hwpx", exchange -> {
            exchange.getResponseHeaders().add(
                    "Content-Type",
                    "application/vnd.hancom.hwpx"
            );
            exchange.sendResponseHeaders(200, hwpxBytes.length);
            exchange.getResponseBody().write(hwpxBytes);
            exchange.close();
        });
        httpServer.start();

        BidAttachmentDto attachment = new BidAttachmentDto(
                "입찰공고문.hwpx",
                "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/notice.hwpx",
                "공고문",
                "NOT_ANALYZED"
        );

        new G2bApiService().analyzeHwpxAttachment(attachment);

        assertEquals("ANALYZED", attachment.getAnalysisStatus());
        assertTrue(attachment.getExternalReferenceDetected());
        assertEquals(1, attachment.getDetectedExternalUrls().size());
        assertEquals("https://example.org/notice", attachment.getDetectedExternalUrls().getFirst());
        assertTrue(attachment.getAnalysisReason().contains("기관 홈페이지 참조"));
        assertTrue(attachment.getAnalysisReason().contains("직접 제출"));
        assertFalse(attachment.getAnalysisReason().contains("g2b.go.kr"));
        assertEquals("ANALYZED", attachment.getDocumentAnalysis().getAnalysisStatus());
        assertTrue(attachment.getDocumentAnalysis().getSubmissionMethods().stream()
                .anyMatch(value -> value.contains("직접 제출")));
    }

    @Test
    void leavesHwpAttachmentNotAnalyzed() {
        BidAttachmentDto attachment = new BidAttachmentDto(
                "입찰공고문.hwp",
                "https://example.org/notice.hwp",
                "공고문",
                "NOT_ANALYZED"
        );

        new G2bApiService().analyzeHwpxAttachment(attachment);

        assertEquals("NOT_ANALYZED", attachment.getAnalysisStatus());
        assertFalse(attachment.getExternalReferenceDetected());
    }

    /** 테스트용 HWPX의 최소 ZIP 구조와 본문 section XML을 메모리에서 생성한다. */
    private byte[] createHwpx(String text) throws Exception {
        String sectionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
                        xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>%s</hp:t></hp:run></hp:p>
                </hs:sec>
                """.formatted(text);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zipOutputStream.write(sectionXml.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.finish();
            return outputStream.toByteArray();
        }
    }
}
