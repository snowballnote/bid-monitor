package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServicePdfAttachmentAnalysisTests {

    private HttpServer httpServer;

    @AfterEach
    void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void detectsExternalUrlAndExcludesG2bUrlFromPdf() throws Exception {
        byte[] pdfBytes = createPdf(
                "See https://example.org/notice and https://www.g2b.go.kr/notice"
        );
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/notice.pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, pdfBytes.length);
            exchange.getResponseBody().write(pdfBytes);
            exchange.close();
        });
        httpServer.start();

        BidAttachmentDto attachment = new BidAttachmentDto(
                "제안요청서.pdf",
                "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/notice.pdf",
                "제안요청서",
                "NOT_ANALYZED"
        );

        G2bApiServiceAttachmentSecurityTests.localService().analyzePdfAttachment(attachment);

        assertEquals("ANALYZED", attachment.getAnalysisStatus());
        assertTrue(attachment.getExternalReferenceDetected());
        assertEquals(1, attachment.getDetectedExternalUrls().size());
        assertEquals("https://example.org/notice", attachment.getDetectedExternalUrls().getFirst());
        assertFalse(attachment.getAnalysisReason().contains("g2b.go.kr"));
        assertEquals("ANALYZED", attachment.getDocumentAnalysis().getAnalysisStatus());
        assertTrue(attachment.getDocumentAnalysis().getRequiredDocuments().isEmpty());
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 750);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}
