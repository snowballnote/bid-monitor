package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class G2bApiServiceAttachmentSecurityTests {
    private HttpServer server;

    static G2bApiService localService() {
        G2bApiService service = new G2bApiService();
        // 로컬 HTTP fixture에만 허용 목록을 대체한다. 운영 기본값은 별도로 검증한다.
        ReflectionTestUtils.setField(service, "allowedAttachmentHosts", Set.of("127.0.0.1"));
        return service;
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void validatesExactHostsProtocolsAndUserInfo() {
        G2bApiService service = new G2bApiService();
        for (String url : new String[]{"https://www.g2b.go.kr/file", "http://g2b.go.kr/file",
                "https://WWW.G2B.GO.KR/file"}) {
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validateAttachmentUrl", URI.create(url)));
        }
        for (String url : new String[]{"file:///etc/passwd", "ftp://www.g2b.go.kr/file",
                "http://127.0.0.1/file", "http://169.254.169.254/", "http://10.0.0.1/",
                "https://www.g2b.go.kr.evil.test/file", "https://evilg2b.go.kr/file",
                "https://user@www.g2b.go.kr/file"}) {
            assertThrows(Exception.class,
                    () -> ReflectionTestUtils.invokeMethod(service, "validateAttachmentUrl", URI.create(url)), url);
        }
    }

    @Test
    void rejectsInitialUrlBeforeMakingRequestForAllFormats() throws Exception {
        startServer();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/file", exchange -> { hits.incrementAndGet(); exchange.close(); });
        for (String format : new String[]{"pdf", "hwpx", "hwp"}) {
            BidAttachmentDto attachment = attachment(format, "/file");
            analyze(new G2bApiService(), attachment, format);
            assertEquals("FAILED", attachment.getAnalysisStatus());
            assertTrue(attachment.getAnalysisReason().contains("허용되지 않은"));
        }
        assertEquals(0, hits.get());
    }

    @Test
    void rejectsRedirectToUnapprovedHostBeforeRequest() throws Exception {
        startServer();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/blocked");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/blocked", exchange -> { hits.incrementAndGet(); exchange.close(); });
        for (String format : new String[]{"pdf", "hwpx", "hwp"}) {
            BidAttachmentDto attachment = attachment(format, "/redirect");
            analyze(localService(), attachment, format);
            assertTrue(attachment.getAnalysisReason().contains("허용되지 않은"));
        }
        assertEquals(0, hits.get());
    }

    @Test
    void followsRelativeAndApprovedAbsoluteRedirects() throws Exception {
        startServer();
        server.createContext("/relative", exchange -> {
            exchange.getResponseHeaders().add("Location", "/absolute");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/absolute", exchange -> {
            exchange.getResponseHeaders().add("Location", url("/file"));
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        byte[] expected = "attachment".getBytes(StandardCharsets.UTF_8);
        serve("/file", expected);
        assertArrayEquals(expected, ReflectionTestUtils.invokeMethod(localService(), "downloadHwpAttachment", url("/relative")));
    }

    @Test
    void limitsRedirectLoops() throws Exception {
        startServer();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/loop", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        BidAttachmentDto attachment = attachment("hwp", "/loop");
        localService().analyzeHwpAttachment(attachment);
        assertTrue(attachment.getAnalysisReason().contains("리다이렉트 횟수"));
        assertEquals(6, hits.get());
    }

    @Test
    void limitsActualChunkedDownloadBytesForAllFormats() throws Exception {
        startServer();
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0); // Content-Length 없이 chunked 응답
            try (var output = exchange.getResponseBody()) {
                byte[] block = new byte[8192];
                for (int i = 0; i < 2561; i++) output.write(block);
            } catch (IOException ignored) {
                // 제한에 도달한 클라이언트가 연결을 닫을 수 있다.
            } finally { exchange.close(); }
        });
        for (String format : new String[]{"pdf", "hwpx", "hwp"}) {
            BidAttachmentDto attachment = attachment(format, "/large");
            analyze(localService(), attachment, format);
            assertEquals("FAILED", attachment.getAnalysisStatus());
            assertTrue(attachment.getAnalysisReason().contains("파일 크기"));
        }
    }

    @Test
    void limitsZipEntryCountIncludingNonSections() throws Exception {
        assertZipRejected(createZip(1001, 0), "항목 수");
    }

    @Test
    void limitsCumulativeInflationAcrossNonSectionEntries() throws Exception {
        assertZipRejected(createZip(3, 18 * 1024 * 1024), "누적 해제량");
    }

    @Test
    void retainsHwpSignatureValidation() throws Exception {
        startServer();
        serve("/invalid", "not hwp".getBytes(StandardCharsets.UTF_8));
        BidAttachmentDto attachment = attachment("hwp", "/invalid");
        localService().analyzeHwpAttachment(attachment);
        assertEquals("FAILED", attachment.getAnalysisStatus());
        assertTrue(attachment.getAnalysisReason().contains("HWP 5.x 파일 서명"));
    }

    private void assertZipRejected(byte[] bytes, String reason) throws Exception {
        startServer();
        serve("/zip", bytes);
        BidAttachmentDto attachment = attachment("hwpx", "/zip");
        localService().analyzeHwpxAttachment(attachment);
        assertEquals("FAILED", attachment.getAnalysisStatus());
        assertTrue(attachment.getAnalysisReason().contains(reason), attachment.getAnalysisReason());
    }

    private byte[] createZip(int entries, int entryBytes) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] block = new byte[8192];
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new ZipEntry("BinData/file" + i));
                for (int remaining = entryBytes; remaining > 0; remaining -= block.length) {
                    zip.write(block, 0, Math.min(remaining, block.length));
                }
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    private BidAttachmentDto attachment(String format, String path) {
        return new BidAttachmentDto("공고문." + format, url(path), "공고문", "NOT_ANALYZED");
    }

    private void serve(String path, byte[] bytes) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void analyze(G2bApiService service, BidAttachmentDto attachment, String format) {
        switch (format) {
            case "pdf" -> service.analyzePdfAttachment(attachment);
            case "hwpx" -> service.analyzeHwpxAttachment(attachment);
            case "hwp" -> service.analyzeHwpAttachment(attachment);
            default -> throw new IllegalArgumentException(format);
        }
    }
}
