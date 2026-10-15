
package com.comhu.bidmonitor.bid.source.kogas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class KogasOpenApiClient {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String serviceKey;

    public KogasOpenApiClient(
            @Value("${kogas.api.base-url}") String baseUrl,
            @Value("${kogas.api.service-key:}") String serviceKey
    ) {
        this.baseUri = URI.create(baseUrl);
        if (!"https".equals(baseUri.getScheme())
                || !"apis.data.go.kr".equals(baseUri.getHost())
                || !"/B551210/bidInfoList2/getBidInfoList2"
                .equals(baseUri.getPath())
                || baseUri.getRawQuery() != null) {
            throw new IllegalArgumentException("Invalid KOGAS API URL");
        }

        this.serviceKey = serviceKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String fetchPage(
            String startDate,
            String endDate,
            int pageNo,
            int numOfRows
    ) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("KOGAS API key is missing");
        }

        if (startDate == null || startDate.isBlank()
                || endDate == null || endDate.isBlank()
                || pageNo < 1 || numOfRows < 1 || numOfRows > 100) {
            throw new IllegalArgumentException("Invalid API parameters");
        }

        // 공공데이터포털 인증키는 URL 인코딩 후 요청한다.
        String query = "serviceKey=" + encode(serviceKey)
                + "&numOfRows=" + numOfRows
                + "&pageNo=" + pageNo
                + "&DOCDATE_START=" + encode(startDate)
                + "&DOCDATE_END=" + encode(endDate);

        URI uri = URI.create(baseUri + "?" + query);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofInputStream()
                    );

            try (var body = response.body()) {

                // 비정상 응답은 성공으로 처리하지 않는다.
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "KOGAS API HTTP error: " + response.statusCode()
                    );
                }

                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("KOGAS response too large");
                }

                return new String(bytes, StandardCharsets.UTF_8);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KOGAS request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("KOGAS request failed");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
