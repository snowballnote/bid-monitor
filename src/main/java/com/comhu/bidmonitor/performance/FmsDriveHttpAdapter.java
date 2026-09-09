package com.comhu.bidmonitor.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.comhu.bidmonitor.performance.FmsDriveException.*;

@Component
public class FmsDriveHttpAdapter implements FmsDrivePort {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FmsDriveHttpAdapter.class);
    private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;
    private final FmsDriveProperties properties;
    private final HttpClient client;
    private static HttpClient defaultClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(); }
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public FmsDriveHttpAdapter(FmsDriveProperties properties) { this(properties, defaultClient()); }
    FmsDriveHttpAdapter(FmsDriveProperties properties, HttpClient client) { this.properties = properties; this.client = client; }
    private static Stage stage(String endpoint) {
        return switch (endpoint) {
            case "/api/drive/list" -> Stage.LIST;
            case "/api/drive/permission" -> Stage.PERMISSION;
            case "/api/drive/download" -> Stage.DOWNLOAD;
            default -> Stage.REFRESH;
        };
    }

    @Override
    public List<Item> list(String folder) {
        String parent = normalizedPath(folder);
        JsonNode body = json("/api/drive/list", parent, true);
        if (!body.isArray()) throw new FmsDriveException("FMS 목록 응답 형식을 확인하세요.");
        List<Item> items = new ArrayList<>();
        for (JsonNode row : body) {
            if (!row.path("name").isTextual() || !row.path("path").isTextual()
                    || !row.path("directory").isBoolean() || !row.path("size").isIntegralNumber()) {
                throw new FmsDriveException("FMS 목록 응답에 필요한 파일정보가 없습니다.");
            }
            String path = normalizedPath(row.path("path").asText());
            String name = row.path("name").asText();
            // A list response may only describe direct children of the requested folder.
            if (!parentOf(path).equals(parent) || !path.substring(path.lastIndexOf('/') + 1).equals(name)) {
                throw new FmsDriveException("FMS 목록의 파일 경로가 검색 폴더와 일치하지 않습니다.");
            }
            Instant modified = null;
            if (row.path("lastModified").isTextual()) {
                try { modified = Instant.parse(row.path("lastModified").asText()); }
                catch (RuntimeException ignored) { /* Timestamp is optional matching metadata. */ }
            }
            items.add(new Item(name, path, row.path("directory").asBoolean(), row.path("size").asLong(), modified));
        }
        return items;
    }

    @Override
    public boolean canDownload(String path) {
        JsonNode body = json("/api/drive/permission", normalizedPath(path), false);
        if (!body.path("canDownload").isBoolean()) throw new FmsDriveException("FMS 다운로드 권한을 확인할 수 없습니다.");
        return body.path("canDownload").asBoolean();
    }

    @Override
    public InputStream download(String path) {
        return response("/api/drive/download", normalizedPath(path), false);
    }

    private JsonNode json(String endpoint, String path, boolean company) {
        try (InputStream input = response(endpoint, path, company)) {
            byte[] bytes = input.readNBytes(MAX_LIST_BYTES + 1);
            if (bytes.length > MAX_LIST_BYTES) throw new FmsDriveException("FMS 목록이 너무 큽니다. 검색 폴더를 더 좁게 설정하세요.");
            JsonNode parsed = mapper.readTree(bytes);
            if (parsed == null) throw new FmsDriveException("FMS 응답이 비어 있습니다.");
            return parsed;
        } catch (FmsDriveException exception) { throw exception; }
        catch (Exception exception) { throw new FmsDriveException("FMS 응답을 읽을 수 없습니다.").details(stage(endpoint), exception instanceof java.net.SocketTimeoutException ? Kind.TIMEOUT : Kind.INVALID_RESPONSE, null, exception); }
    }

    private InputStream response(String endpoint, String path, boolean company) {
        long started = System.nanoTime();
        boolean listRequest = "/api/drive/list".equals(endpoint);
        try {
            String base = properties.getBaseUrl().replaceAll("/+$", "");
            URI configured = URI.create(base);
            String session = properties.getSessionToken();
            if (!List.of("http", "https").contains(configured.getScheme()) || configured.getHost() == null
                    || configured.getUserInfo() != null || configured.getQuery() != null || configured.getFragment() != null
                    || session.isBlank() || session.contains(";") || session.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw new FmsDriveException("FMS 서버 주소와 세션 설정을 확인하세요.").details(stage(endpoint), Kind.INVALID_CONFIG, null, null);
            }
            String query = "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
            if (company) query += "&company=" + URLEncoder.encode(properties.getCompany(), StandardCharsets.UTF_8);
            var builder = HttpRequest.newBuilder(URI.create(base + endpoint + query))
                    .timeout(Duration.ofSeconds(20)).header("Cookie", "SESSION=" + session).GET();
            // The deployed HTTP reverse proxy stalls on Java's cleartext HTTP/2 upgrade.
            if (listRequest) builder.version(HttpClient.Version.HTTP_1_1);
            HttpRequest request = builder.build();
            if (listRequest) {
                URI uri = request.uri();
                log.info("FMS list start: scheme={} host={} port={} endpoint=/api/drive/list basePathPresent={} companyPresent={} sessionPresent={} redirect={} preferredVersion={} proxy={}",
                        uri.getScheme(), uri.getHost(), uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(),
                        !configured.getPath().isEmpty() && !"/".equals(configured.getPath()),
                        company && !properties.getCompany().isBlank(),
                        request.headers().firstValue("Cookie").filter(value -> value.startsWith("SESSION=") && value.length() > 8).isPresent(),
                        client.followRedirects(), request.version().orElse(client.version()), proxySummary(uri));
            }
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (listRequest) log.info("FMS list response: status={} elapsedMs={} version={}",
                    response.statusCode(), (System.nanoTime() - started) / 1_000_000, response.version());
            if (response.statusCode() != 200) {
                response.body().close();
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new FmsDriveException("FMS 인증 또는 접근 권한을 확인하세요.", true).details(stage(endpoint), Kind.HTTP_ERROR, response.statusCode(), null);
                }
                throw new FmsDriveException("FMS 조회 또는 다운로드를 완료하지 못했습니다.").details(stage(endpoint), Kind.HTTP_ERROR, response.statusCode(), null);
            }
            return response.body();
        } catch (FmsDriveException exception) {
            if (listRequest) log.warn("FMS list failure: kind={} status={} elapsedMs={}",
                    exception.kind(), exception.httpStatus(), (System.nanoTime() - started) / 1_000_000);
            throw exception;
        }
        catch (InterruptedException exception) {
            if (listRequest) log.warn("FMS list failure: kind=INTERRUPTED elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
            Thread.currentThread().interrupt();
            throw new FmsDriveException("FMS 요청이 중단되었습니다.").details(stage(endpoint), Kind.INTERRUPTED, null, exception);
        } catch (Exception exception) {
            Kind kind = exception instanceof java.net.http.HttpTimeoutException || exception instanceof java.net.SocketTimeoutException
                    ? Kind.TIMEOUT : exception instanceof java.net.ConnectException ? Kind.CONNECTION_REFUSED
                    : exception instanceof IllegalArgumentException ? Kind.INVALID_CONFIG : Kind.IO_ERROR;
            if (listRequest) log.warn("FMS list failure: kind={} elapsedMs={}", kind, (System.nanoTime() - started) / 1_000_000);
            throw new FmsDriveException("FMS 연결 설정과 서버 상태를 확인하세요.").details(stage(endpoint), kind, null, exception);
        }
    }

    private String proxySummary(URI uri) {
        try {
            var selector = client.proxy().orElse(java.net.ProxySelector.getDefault());
            if (selector == null) return "DIRECT";
            return selector.select(uri).stream().map(proxy -> {
                if (proxy.type() == java.net.Proxy.Type.DIRECT) return "DIRECT";
                if (proxy.address() instanceof java.net.InetSocketAddress address)
                    return proxy.type() + ":" + address.getHostString() + ":" + address.getPort();
                return proxy.type().name();
            }).collect(java.util.stream.Collectors.joining(","));
        } catch (RuntimeException ignored) { return "UNKNOWN"; }
    }
    static String normalizedPath(String value) {
        if (value == null || !value.startsWith("/") || value.contains("\\")
                || value.chars().anyMatch(c -> c < 32) || value.contains("//")) {
            throw new FmsDriveException("FMS 검색 폴더 또는 파일 경로를 확인하세요.").details(Stage.VALIDATION, Kind.INVALID_ROOT, null, null);
        }
        String path = value.length() > 1 ? value.replaceAll("/+$", "") : value;
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) throw new FmsDriveException("FMS 경로 범위를 확인하세요.").details(Stage.VALIDATION, Kind.INVALID_ROOT, null, null);
        }
        return path;
    }

    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }
}