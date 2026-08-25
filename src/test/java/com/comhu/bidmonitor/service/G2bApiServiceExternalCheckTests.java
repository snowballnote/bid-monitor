package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceExternalCheckTests {

    private HttpServer httpServer;

    @AfterEach
    void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void marksRequiredAndRemovesDuplicateExternalUrls() throws Exception {
        String attachmentUrl = serveHwpx(
                "기관 홈페이지 참조 https://www.gokea.org/ko/ "
                        + "https://www.gokea.org/ko/ https://www.g2b.go.kr/notice"
        );
        BidQualificationDto qualification = qualificationWithAttachment(
                "입찰공고문.hwpx",
                attachmentUrl,
                "공고문"
        );

        applyExternalCheckResult(qualification);

        assertEquals("REQUIRED", qualification.getExternalCheckStatus());
        assertTrue(qualification.getExternalSiteCheckRequired());
        assertEquals(List.of("https://www.gokea.org/ko/"), qualification.getExternalSiteUrls());
        assertFalse(qualification.getExternalCheckReason().contains("g2b.go.kr"));
        assertEquals("ANALYZED", qualification.getAttachments().getFirst().getAnalysisStatus());
    }

    @Test
    void marksReferenceWhenOnlySmppUrlIsDetected() throws Exception {
        String attachmentUrl = serveHwpx("자격 확인 https://www.smpp.go.kr/guide");
        BidQualificationDto qualification = qualificationWithAttachment(
                "입찰공고문.hwpx",
                attachmentUrl,
                "공고문"
        );

        applyExternalCheckResult(qualification);

        assertEquals("REFERENCE", qualification.getExternalCheckStatus());
        assertFalse(qualification.getExternalSiteCheckRequired());
        assertEquals(List.of("https://www.smpp.go.kr/guide"), qualification.getExternalSiteUrls());
        assertTrue(qualification.getExternalCheckReason().contains("자격·제도 확인용 참고사이트"));
    }

    @Test
    void requiredUrlTakesPriorityOverSmppReferenceUrl() throws Exception {
        String attachmentUrl = serveHwpx(
                "https://smpp.go.kr/guide https://www.kes.org/kor/"
        );
        BidQualificationDto qualification = qualificationWithAttachment(
                "제안요청서.hwpx",
                attachmentUrl,
                "제안요청서"
        );

        applyExternalCheckResult(qualification);

        assertEquals("REQUIRED", qualification.getExternalCheckStatus());
        assertTrue(qualification.getExternalSiteCheckRequired());
        assertEquals(
                List.of("https://smpp.go.kr/guide", "https://www.kes.org/kor/"),
                qualification.getExternalSiteUrls()
        );
    }

    @Test
    void g2bUrlAloneIsNotDetected() throws Exception {
        String attachmentUrl = serveHwpx("관련 시스템 https://www.g2b.go.kr/notice");
        BidQualificationDto qualification = qualificationWithAttachment(
                "입찰공고문.hwpx",
                attachmentUrl,
                "공고문"
        );

        applyExternalCheckResult(qualification);

        assertEquals("NOT_DETECTED", qualification.getExternalCheckStatus());
        assertFalse(qualification.getExternalSiteCheckRequired());
        assertTrue(qualification.getExternalSiteUrls().isEmpty());
    }

    @Test
    void marksNotDetectedWhenAnalyzedAttachmentHasNoSignal() throws Exception {
        String attachmentUrl = serveHwpx("입찰 참가자격과 제출서류를 확인합니다.");
        BidQualificationDto qualification = qualificationWithAttachment(
                "과업지시서.hwpx",
                attachmentUrl,
                "과업지시서"
        );

        applyExternalCheckResult(qualification);

        assertEquals("NOT_DETECTED", qualification.getExternalCheckStatus());
        assertFalse(qualification.getExternalSiteCheckRequired());
        assertTrue(qualification.getExternalSiteUrls().isEmpty());
    }

    @Test
    void marksUnknownWhenHwpAttachmentAnalysisFails() throws Exception {
        String attachmentUrl = serveFile("손상된 HWP".getBytes(StandardCharsets.UTF_8), "/notice.hwp");
        BidQualificationDto qualification = qualificationWithAttachment(
                "입찰공고문.hwp",
                attachmentUrl,
                "공고문"
        );

        applyExternalCheckResult(qualification);

        assertEquals("UNKNOWN", qualification.getExternalCheckStatus());
        assertNull(qualification.getExternalSiteCheckRequired());
        assertEquals("FAILED", qualification.getAttachments().getFirst().getAnalysisStatus());
    }

    private BidQualificationDto qualificationWithAttachment(
            String fileName,
            String fileUrl,
            String documentType
    ) {
        BidQualificationDto qualification = new BidQualificationDto();
        qualification.setAttachments(List.of(new BidAttachmentDto(
                fileName,
                fileUrl,
                documentType,
                "NOT_ANALYZED"
        )));
        return qualification;
    }

    private String serveHwpx(String text) throws Exception {
        byte[] hwpxBytes = createHwpx(text);
        return serveFile(hwpxBytes, "/notice.hwpx");
    }

    private String serveFile(byte[] fileBytes, String path) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, fileBytes.length);
            exchange.getResponseBody().write(fileBytes);
            exchange.close();
        });
        httpServer.start();
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + path;
    }

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

    private void applyExternalCheckResult(BidQualificationDto qualification) throws Exception {
        Method method = G2bApiService.class.getDeclaredMethod(
                "applyExternalCheckResult",
                BidQualificationDto.class
        );
        method.setAccessible(true);
        method.invoke(new G2bApiService(), qualification);
    }
}
