package com.comhu.bidmonitor.bid.source.korail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KorailBidHttpClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void createsPostRequestForConfirmedEndpointAndContentType() {
        KorailBidHttpClient client = client(request -> new KorailBidHttpClient.Response(200, emptyResponse()));

        HttpRequest request = client.createListRequest(
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 10, 16)
        );

        assertEquals("POST", request.method());
        assertEquals(URI.create("https://example.test/comnRfc.do"), request.uri());
        assertEquals(KorailBidHttpClient.FORM_CONTENT_TYPE,
                request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
        assertFalse(request.headers().firstValue("Cookie").isPresent());
        assertFalse(request.headers().firstValue("Authorization").isPresent());
    }

    @Test
    void formEncodesFunctionServiceCodeAndDates() throws Exception {
        KorailBidHttpClient client = client(request -> new KorailBidHttpClient.Response(200, emptyResponse()));

        HttpRequest request = client.createListRequest(
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 10, 16)
        );
        Map<String, String> form = decodeForm(readBody(request));
        JsonNode function = objectMapper.readTree(form.get("fn"));
        JsonNode parameters = objectMapper.readTree(form.get("p"));

        assertEquals("ZMME_EBID_INFO_0009F", function.path("ZFUNCNM").asText());
        assertEquals("5", parameter(parameters, "I_ZZBIDTYPECODE"));
        assertEquals("20260817", parameter(parameters, "I_ERDAT_FR"));
        assertEquals("20261016", parameter(parameters, "I_ERDAT_TO"));
        assertEquals("", parameter(parameters, "I_DESCRIPTION"));
        assertEquals(10, parameters.size());
    }

    @Test
    void createsEncodedDetailRequestForCurrentNotice() throws Exception {
        KorailBidHttpClient client = client(
                request -> new KorailBidHttpClient.Response(200, detailResponse("9258430-00", "00"))
        );

        HttpRequest request = client.createDetailRequest("9258430-00", "00");
        Map<String, String> form = decodeForm(readBody(request));
        JsonNode function = objectMapper.readTree(form.get("fn"));
        JsonNode parameters = objectMapper.readTree(form.get("p"));

        assertEquals("POST", request.method());
        assertEquals(URI.create("https://example.test/comnRfc.do"), request.uri());
        assertEquals(KorailBidHttpClient.FORM_CONTENT_TYPE,
                request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
        assertEquals("ZMME_EBID_INFO_0010F", function.path("ZFUNCNM").asText());
        assertEquals("9258430-00", parameter(parameters, "I_ZBIDINV"));
        assertEquals("00", parameter(parameters, "I_ZESTNUMM"));
        assertEquals("", parameter(parameters, "I_ERDAT"));
        assertEquals("", parameter(parameters, "I_LIFNR"));
        assertEquals(4, parameters.size());
    }

    @Test
    void fetchesDetailThroughExistingTransportAndParser() {
        KorailBidHttpClient client = client(
                request -> new KorailBidHttpClient.Response(200, detailResponse("9258430-00", "00"))
        );

        KorailBidDetailParser.ParsedDetail detail = client.fetchDetail("9258430-00", "00");

        assertEquals("9258430-00", detail.detail().noticeNo());
        assertEquals("00", detail.detail().round());
        assertEquals(4, detail.attachments().size());
        assertEquals(5, detail.noticeLines().size());
        assertEquals("line-1\nline-2\nline-3\nline-4\nline-5", detail.noticeText());
    }

    @Test
    void rejectsDetailWhoseResponseIdentityDoesNotMatchRequest() {
        KorailBidHttpClient wrongNotice = client(
                request -> new KorailBidHttpClient.Response(200, detailResponse("DIFFERENT-00", "00"))
        );
        KorailBidHttpClient wrongRound = client(
                request -> new KorailBidHttpClient.Response(200, detailResponse("9258430-00", "01"))
        );

        assertThrows(IllegalStateException.class, () -> wrongNotice.fetchDetail("9258430-00", "00"));
        assertThrows(IllegalStateException.class, () -> wrongRound.fetchDetail("9258430-00", "00"));
    }

    @Test
    void rejectsDetailHttpErrorAndDataErrorWithoutExposingBody() {
        KorailBidHttpClient httpError = client(
                request -> new KorailBidHttpClient.Response(502, "sensitive response body")
        );
        KorailBidHttpClient dataError = client(
                request -> new KorailBidHttpClient.Response(200, "{\"data\":\"error\"}")
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> httpError.fetchDetail("9258430-00", "00")
        );
        assertTrue(exception.getMessage().contains("HTTP 502"));
        assertFalse(exception.getMessage().contains("sensitive response body"));
        assertThrows(IllegalStateException.class, () -> dataError.fetchDetail("9258430-00", "00"));
    }

    @Test
    void rejectsBlankDetailIdentityBeforeTransport() {
        boolean[] called = {false};
        KorailBidHttpClient client = client(request -> {
            called[0] = true;
            return new KorailBidHttpClient.Response(200, detailResponse("9258430-00", "00"));
        });

        List<String> invalidValues = java.util.Arrays.asList(null, "", "   ");
        for (String invalid : invalidValues) {
            assertThrows(IllegalArgumentException.class, () -> client.fetchDetail(invalid, "00"));
            assertThrows(IllegalArgumentException.class, () -> client.fetchDetail("9258430-00", invalid));
        }
        assertFalse(called[0]);
    }

    @Test
    void rejectsInvalidDateRangesBeforeTransport() {
        boolean[] called = {false};
        KorailBidHttpClient client = client(request -> {
            called[0] = true;
            return new KorailBidHttpClient.Response(200, emptyResponse());
        });

        assertThrows(IllegalArgumentException.class, () -> client.fetchList(
                LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 16)
        ));
        assertThrows(IllegalArgumentException.class, () -> client.fetchList(
                null, LocalDate.of(2026, 10, 16)
        ));
        assertFalse(called[0]);
    }

    @Test
    void rejectsNonSuccessfulHttpStatusWithoutParsingBody() {
        KorailBidHttpClient client = client(request -> new KorailBidHttpClient.Response(503, "sensitive body"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.fetchList(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)
        ));

        assertTrue(exception.getMessage().contains("HTTP 503"));
        assertFalse(exception.getMessage().contains("sensitive body"));
    }

    @Test
    void rejectsDataErrorAfterSuccessfulHttpResponse() {
        KorailBidHttpClient client = client(
                request -> new KorailBidHttpClient.Response(200, "{\"data\":\"error\"}")
        );

        assertThrows(IllegalStateException.class, () -> client.fetchList(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)
        ));
    }

    @Test
    void passesSuccessfulResponseToExistingParser() {
        String response = responseWithNotice("0001234-00", "00");
        KorailBidHttpClient client = client(request -> new KorailBidHttpClient.Response(200, response));

        KorailBidListParser.Notice notice = client.fetchList(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)
        ).getFirst();

        assertEquals("0001234-00", notice.noticeNo());
        assertEquals("00", notice.round());
    }

    @Test
    void wrapsIoFailureWithoutIncludingRequestOrResponseData() {
        KorailBidHttpClient client = client(request -> {
            throw new IOException("low-level transport detail");
        });

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.fetchList(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)
        ));

        assertEquals("KORAIL list request failed.", exception.getMessage());
    }

    @Test
    void restoresInterruptedFlagWhenTransportIsInterrupted() {
        KorailBidHttpClient client = client(request -> {
            throw new InterruptedException("interrupted");
        });

        assertThrows(IllegalStateException.class, () -> client.fetchList(
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)
        ));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private KorailBidHttpClient client(KorailBidHttpClient.Transport transport) {
        return new KorailBidHttpClient(
                "https://example.test/",
                objectMapper,
                new KorailBidListParser(objectMapper),
                transport
        );
    }

    private String emptyResponse() {
        return responseWithRows(objectMapper.createArrayNode());
    }

    private String responseWithNotice(String noticeNo, String round) {
        ArrayNode rows = objectMapper.createArrayNode();
        rows.addObject().put("zzbidinv", noticeNo).put("zzstnum", round);
        return responseWithRows(rows);
    }

    private String responseWithRows(ArrayNode rows) {
        try {
            ArrayNode itab = objectMapper.createArrayNode();
            itab.addObject().put("name", "E_SUBRC").put("value", "S");
            itab.addObject().put("name", "ET_ZSMMEEBID0083").set("value", rows);
            ObjectNode inner = objectMapper.createObjectNode().set("itab", itab);
            return objectMapper.createObjectNode()
                    .put("data", objectMapper.writeValueAsString(inner))
                    .toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String detailResponse(String noticeNo, String round) {
        try {
            ArrayNode detailRows = objectMapper.createArrayNode();
            detailRows.addObject()
                    .put("zzbidinv", noticeNo)
                    .put("zzstnum", round)
                    .put("description", "service notice")
                    .put("zzbid_edat", "20261028")
                    .put("zzbid_etim", "10:00:00");

            ArrayNode documents = objectMapper.createArrayNode();
            for (int index = 1; index <= 4; index++) {
                documents.addObject()
                        .put("filnm", "file-" + index + ".hwp")
                        .put("flidx", index)
                        .put("doknr", "00000000000000000" + index)
                        .put("dokar", "BID")
                        .put("dokvr", "00")
                        .put("filid", "F" + index);
            }

            ArrayNode lines = objectMapper.createArrayNode();
            for (int index = 1; index <= 5; index++) {
                lines.addObject().put("tdformat", "*").put("tdline", "line-" + index);
            }

            ArrayNode itab = objectMapper.createArrayNode();
            itab.addObject().put("name", "E_SUBRC").put("value", "S");
            itab.addObject().put("name", "ET_RESULT").set("value", objectMapper.createArrayNode());
            itab.addObject().put("name", "ET_ZSMMEEBID0004").set("value", detailRows);
            itab.addObject().put("name", "ET_DOCLIST").set("value", documents);
            itab.addObject().put("name", "ET_LONG_TEXT").set("value", lines);
            ObjectNode inner = objectMapper.createObjectNode().set("itab", itab);
            return objectMapper.createObjectNode()
                    .put("data", objectMapper.writeValueAsString(inner))
                    .toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String parameter(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter.path("value_string").asText();
            }
        }
        throw new AssertionError("Missing parameter: " + name);
    }

    private Map<String, String> decodeForm(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            fields.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            );
        }
        return fields;
    }

    private String readBody(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder body = new StringBuilder();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(body.toString());
            }
        });
        return result.get();
    }
}
