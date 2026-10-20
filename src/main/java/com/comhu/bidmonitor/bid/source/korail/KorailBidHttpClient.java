package com.comhu.bidmonitor.bid.source.korail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Sends KORAIL service-bid requests and delegates response parsing.
 * One client instance owns one cookie store. Public-session initialization is serialized and
 * performed once; this class does not add request retries or parallel execution.
 */
public final class KorailBidHttpClient {

    static final String DEFAULT_BASE_URL = "https://ebid.korail.com";
    static final String RFC_PATH = "/comnRfc.do";
    static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8";

    private static final String LIST_FUNCTION = "ZMME_EBID_INFO_0009F";
    private static final String DETAIL_FUNCTION = "ZMME_EBID_INFO_0010F";
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final KorailBidListParser parser;
    private final KorailBidDetailParser detailParser;
    private final Transport transport;
    private final Object sessionInitializationLock = new Object();
    private volatile boolean sessionInitialized;

    public KorailBidHttpClient(ObjectMapper objectMapper) {
        this(DEFAULT_BASE_URL, objectMapper);
    }

    KorailBidHttpClient(String baseUrl, ObjectMapper objectMapper) {
        this(
                baseUrl,
                objectMapper,
                new KorailBidListParser(objectMapper),
                new KorailBidDetailParser(objectMapper),
                createJdkTransport()
        );
    }

    KorailBidHttpClient(
            String baseUrl,
            ObjectMapper objectMapper,
            KorailBidListParser parser,
            Transport transport
    ) {
        this(baseUrl, objectMapper, parser, new KorailBidDetailParser(objectMapper), transport);
    }

    KorailBidHttpClient(
            String baseUrl,
            ObjectMapper objectMapper,
            KorailBidListParser parser,
            KorailBidDetailParser detailParser,
            Transport transport
    ) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.detailParser = Objects.requireNonNull(detailParser, "detailParser");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public List<KorailBidListParser.Notice> fetchList(LocalDate startDate, LocalDate endDate) {
        HttpRequest request = createListRequest(startDate, endDate);
        ensureSessionInitialized();
        return parser.parse(send(request, "list"));
    }

    public KorailBidDetailParser.ParsedDetail fetchDetail(String noticeNo, String round) {
        HttpRequest request = createDetailRequest(noticeNo, round);
        ensureSessionInitialized();
        KorailBidDetailParser.ParsedDetail parsed = detailParser.parse(send(request, "detail"));
        if (!noticeNo.equals(parsed.detail().noticeNo()) || !round.equals(parsed.detail().round())) {
            throw new IllegalStateException("KORAIL detail response identity does not match request.");
        }
        return parsed;
    }

    private String send(HttpRequest request, String operation) {
        Response response;
        try {
            response = transport.send(request);
        } catch (IOException exception) {
            throw new IllegalStateException("KORAIL " + operation + " request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KORAIL " + operation + " request was interrupted.", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "KORAIL " + operation + " request failed with HTTP " + response.statusCode() + "."
            );
        }
        return response.body();
    }

    private void ensureSessionInitialized() {
        if (sessionInitialized) {
            return;
        }
        synchronized (sessionInitializationLock) {
            if (sessionInitialized) {
                return;
            }
            send(createSessionInitializationRequest(), "session initialization");
            sessionInitialized = true;
        }
    }

    HttpRequest createSessionInitializationRequest() {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    HttpRequest createListRequest(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        ArrayNode parameters = objectMapper.createArrayNode();
        addParameter(parameters, "I_ZZBIDTYPECODE", "5");
        addParameter(parameters, "I_ZZCTRMETHOD", "");
        addParameter(parameters, "I_BSART", "");
        addParameter(parameters, "I_ZZBIDINV", "");
        addParameter(parameters, "I_DESCRIPTION", "");
        addParameter(parameters, "I_ERDAT_FR", startDate.format(REQUEST_DATE));
        addParameter(parameters, "I_ERDAT_TO", endDate.format(REQUEST_DATE));
        addParameter(parameters, "I_SDAT_FR", "");
        addParameter(parameters, "I_SDAT_TO", "");
        addParameter(parameters, "I_LIFNR", "");

        return createPostRequest(LIST_FUNCTION, parameters);
    }

    HttpRequest createDetailRequest(String noticeNo, String round) {
        validateDetailIdentity(noticeNo, round);
        ArrayNode parameters = objectMapper.createArrayNode();
        addParameter(parameters, "I_ZBIDINV", noticeNo);
        addParameter(parameters, "I_ZESTNUMM", round);
        addParameter(parameters, "I_ERDAT", "");
        addParameter(parameters, "I_LIFNR", "");
        return createPostRequest(DETAIL_FUNCTION, parameters);
    }

    private HttpRequest createPostRequest(String functionName, ArrayNode parameters) {
        String function = writeJson(objectMapper.createObjectNode().put("ZFUNCNM", functionName));

        String formBody = formField("fn", function) + "&" + formField("p", writeJson(parameters));
        return HttpRequest.newBuilder(URI.create(baseUrl + RFC_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();
    }

    private void addParameter(ArrayNode parameters, String name, String value) {
        parameters.add(objectMapper.createObjectNode()
                .put("value_string", value)
                .put("name", name));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KORAIL request JSON could not be created.", exception);
        }
    }

    private String formField(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("KORAIL list request date range is invalid.");
        }
    }

    private void validateDetailIdentity(String noticeNo, String round) {
        if (noticeNo == null || noticeNo.isBlank()) {
            throw new IllegalArgumentException("KORAIL detail notice number must not be blank.");
        }
        if (round == null || round.isBlank()) {
            throw new IllegalArgumentException("KORAIL detail round must not be blank.");
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("KORAIL base URL must not be blank.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Transport createJdkTransport() {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        return new JdkTransport(HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    @FunctionalInterface
    interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    record Response(int statusCode, String body) {
    }

    private record JdkTransport(HttpClient httpClient) implements Transport {
        private JdkTransport {
            Objects.requireNonNull(httpClient, "httpClient");
        }

        @Override
        public Response send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return new Response(response.statusCode(), response.body());
        }
    }
}
