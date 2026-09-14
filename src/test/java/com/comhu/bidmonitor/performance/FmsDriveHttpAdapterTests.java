package com.comhu.bidmonitor.performance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FmsDriveHttpAdapterTests {
    private HttpServer server;
    private FmsDriveProperties config;
    private FmsDriveHttpAdapter adapter;
    private final List<String> queries = new ArrayList<>();
    private final List<String> cookies = new ArrayList<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger logins = new AtomicInteger();
    private int loginStatus = 200;
    private String sessionCookie = "SESSION=test-session; Path=/; HttpOnly";
    private final List<String> loginBodies = new ArrayList<>();
    private final List<String> loginMethods = new ArrayList<>();
    private boolean expireSession;
    private int status;
    private boolean permission;
    private String listBody;
    private final List<String> upgradeHeaders = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        status = 200; permission = true;
        listBody = """
                [{"name":"한국관광 실적증명원.pdf","path":"/증빙/한국관광 실적증명원.pdf","directory":false,
                  "size":3,"lastModified":"2026-07-01T00:00:00Z","canDownload":false}]
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            logins.incrementAndGet();
            upgradeHeaders.add(exchange.getRequestHeaders().getFirst("Upgrade"));
            loginMethods.add(exchange.getRequestMethod());
            loginBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Set-Cookie", "OTHER=ignored; Path=/");
            if (sessionCookie != null) exchange.getResponseHeaders().add("Set-Cookie", sessionCookie);
            byte[] body = "private-password SESSION=private-session-value".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(loginStatus, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/drive/", exchange -> {
            upgradeHeaders.add(exchange.getRequestHeaders().getFirst("Upgrade"));
            queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            String endpoint = exchange.getRequestURI().getPath();
            byte[] body = (endpoint.endsWith("/list") ? listBody
                    : endpoint.endsWith("/permission") ? "{\"canDownload\":" + permission + "}"
                    : "PDF").getBytes(StandardCharsets.UTF_8);
            if (endpoint.endsWith("/download")) downloads.incrementAndGet();
            int responseStatus = expireSession && "SESSION=test-session".equals(exchange.getRequestHeaders().getFirst("Cookie")) ? 401 : status;
            if (responseStatus != 200) body = "/private/path SESSION=secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        config = new FmsDriveProperties();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setLoginId("test-user");
        config.setPassword("test-password");
        adapter = new FmsDriveHttpAdapter(config);
    }
    @AfterEach void close() { server.stop(0); }

    @Test
    void listUsesCompanyAndSessionWhileDownloadUsesSeparatePermissionDespiteFalseListFlag() throws Exception {
        var items = adapter.list("/증빙");
        assertThat(items).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("한국관광 실적증명원.pdf"));
        assertThat(adapter.canDownload(items.getFirst().path())).isTrue();
        try (var input = adapter.download(items.getFirst().path())) { assertThat(new String(input.readAllBytes())).isEqualTo("PDF"); }
        assertThat(queries).containsExactly("path=/증빙&company=CNH",
                "path=/증빙/한국관광 실적증명원.pdf", "path=/증빙/한국관광 실적증명원.pdf");
        assertThat(cookies).containsOnly("SESSION=test-session");
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(upgradeHeaders).containsOnlyNulls();
        assertThat(logins.get()).isEqualTo(1);
        assertThat(loginMethods).containsExactly("POST");
        var credentials = new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginBodies.getFirst());
        assertThat(credentials.path("loginId").asText()).isEqualTo("test-user");
        assertThat(credentials.path("password").asText()).isEqualTo("test-password");
    }

    @Test
    void eachDriveOperationRetriesOnceWithRenewedSession() throws Exception {
        adapter.list("/증빙");
        for (String operation : List.of("list", "permission", "download")) {
            sessionCookie = "SESSION=test-session; Path=/";
            adapter = new FmsDriveHttpAdapter(config);
            expireSession = false;
            adapter.list("/증빙");
            queries.clear(); cookies.clear();
            int before = logins.get();
            expireSession = true;
            sessionCookie = "SESSION=renewed; Path=/; HttpOnly";
            switch (operation) {
                case "list" -> adapter.list("/증빙");
                case "permission" -> assertThat(adapter.canDownload("/증빙/file.pdf")).isTrue();
                default -> { try (var input = adapter.download("/증빙/file.pdf")) { assertThat(new String(input.readAllBytes())).isEqualTo("PDF"); } }
            }
            assertThat(logins.get()).isEqualTo(before + 1);
            assertThat(cookies).containsExactly("SESSION=test-session", "SESSION=renewed");
            assertThat(queries).hasSize(2);
            assertThat(queries.getFirst()).isEqualTo(queries.getLast());
        }
        assertThat(upgradeHeaders).containsOnlyNulls();
    }

    @Test
    void repeatedUnauthorizedStopsAfterOneRetryAndFailedLoginDoesNotRetryDrive() {
        status = 401;
        assertThatThrownBy(() -> adapter.list("/증빙")).hasMessageContaining("재인증");
        assertThat(logins.get()).isEqualTo(2);
        assertThat(queries).hasSize(2);
        status = 200;
        adapter.list("/증빙");
        int before = logins.get();
        queries.clear();
        status = 401; loginStatus = 401;
        assertThatThrownBy(() -> adapter.list("/증빙")).hasMessageContaining("로그인에 실패")
                .hasMessageNotContaining("private-password").hasMessageNotContaining("private-session-value");
        assertThat(logins.get()).isEqualTo(before + 1);
        assertThat(queries).hasSize(1);
    }

    @Test
    void missingCookieOrCredentialsFailsClearlyAndForbiddenDoesNotLoginAgain() {
        sessionCookie = null;
        assertThatThrownBy(() -> adapter.list("/증빙")).hasMessageContaining("유효한 SESSION");
        assertThat(queries).isEmpty();
        config.setPassword("");
        assertThatThrownBy(() -> adapter.list("/증빙")).hasMessageContaining("비밀번호 설정");
        assertThat(logins.get()).isEqualTo(1);
        config.setPassword("test-password"); sessionCookie = "SESSION=test-session; Path=/";
        status = 403;
        assertThatThrownBy(() -> adapter.list("/증빙")).hasMessageContaining("접근 권한");
        assertThat(logins.get()).isEqualTo(2);
        assertThat(queries).hasSize(1);
    }

    @Test
    void forbiddenUnauthorizedAndOtherErrorsNeverExposeRemoteBodies() {
        for (int code : List.of(401, 403, 404, 500, 302)) {
            status = code;
            var failure = (FmsDriveException) catchThrowable(() -> adapter.list("/증빙"));
            assertThat(failure.httpStatus()).isEqualTo(code);
            assertThat(failure.stage()).isEqualTo(FmsDriveException.Stage.LIST);
            assertThat(failure.kind()).isEqualTo(FmsDriveException.Kind.HTTP_ERROR);
            assertThatThrownBy(() -> adapter.list("/증빙")).isInstanceOf(FmsDriveException.class)
                    .hasMessageNotContaining("secret").hasMessageNotContaining("/private").hasNoCause();
        }
    }

    @Test
    void separatePermissionFalseIsAuthoritativeAndMalformedListDoesNotLookEmpty() {
        permission = false;
        assertThat(adapter.canDownload("/증빙/파일.pdf")).isFalse();
        listBody = "{}";
        assertThatThrownBy(() -> adapter.list("/증빙")).isInstanceOf(FmsDriveException.class);
        listBody = "[{\"name\":\"file.pdf\",\"path\":\"/outside/file.pdf\",\"directory\":false,\"size\":3}]";
        assertThatThrownBy(() -> adapter.list("/증빙")).isInstanceOf(FmsDriveException.class);
    }

    @Test
    void encodesQueryMetacharactersAsPathDataAndRejectsHeaderInjectionAndTraversal() {
        permission = true;
        assertThat(adapter.canDownload("/증빙/a&b+#.pdf")).isTrue();
        sessionCookie = "SESSION=secret,other=value; Path=/";
        adapter = new FmsDriveHttpAdapter(config);
        assertThatThrownBy(() -> adapter.list("/증빙")).isInstanceOf(FmsDriveException.class).hasMessageNotContaining("secret");
        assertThatThrownBy(() -> FmsDriveHttpAdapter.normalizedPath("/증빙/../outside")).isInstanceOf(FmsDriveException.class);
    }
    @Test
    void preservesSafeTransportDiagnosticsAndEndpointStage() throws Exception {
        var client = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        var errors = List.of(new java.net.ConnectException("SESSION=secret /private"),
                new java.net.http.HttpTimeoutException("password=secret /private"));
        for (var error : errors) {
            org.mockito.Mockito.reset(client);
            org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>any())).thenThrow(error);
            var failing = new FmsDriveHttpAdapter(config, client);
            var failure = (FmsDriveException) catchThrowable(() -> failing.canDownload("/private"));
            assertThat(failure.stage()).isEqualTo(FmsDriveException.Stage.PERMISSION);
            assertThat(failure.kind()).isEqualTo(error instanceof java.net.ConnectException
                    ? FmsDriveException.Kind.CONNECTION_REFUSED : FmsDriveException.Kind.TIMEOUT);
            assertThat(failure.diagnostic().getMessage()).isEqualTo(error.getClass().getName());
            assertThat(failure.diagnostic().getStackTrace()).isEqualTo(error.getStackTrace());
            assertThat(failure.getCause()).isNull();
        }
        var invalid = (FmsDriveException) catchThrowable(() -> adapter.list("/private/../secret"));
        assertThat(invalid.kind()).isEqualTo(FmsDriveException.Kind.INVALID_ROOT);
        status = 404;
        var missing = (FmsDriveException) catchThrowable(() -> adapter.download("/private"));
        assertThat(missing.httpStatus()).isEqualTo(404);
        assertThat(missing.stage()).isEqualTo(FmsDriveException.Stage.DOWNLOAD);
    }    @Test
    void listDiagnosticsExposeOnlySafeDestinationAndRequestFlags() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FmsDriveHttpAdapter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            config.setBaseUrl(config.getBaseUrl() + "/");
            sessionCookie = "SESSION=private-session-value; Path=/; HttpOnly";
            adapter.list("/증빙");
            String output = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            assertThat(output).contains("host=127.0.0.1", "port=" + server.getAddress().getPort(),
                    "endpoint=/api/drive/list", "companyPresent=true", "sessionPresent=true",
                    "redirect=NEVER", "preferredVersion=HTTP_1_1", "status=200", "elapsedMs=", "proxy=");
            assertThat(output).doesNotContain("private-session-value", "test-password", "test-user", "/증빙", "%EC", "Cookie:", "SESSION=");
            assertThat(cookies).containsOnly("SESSION=private-session-value");
            assertThat(queries).containsExactly("path=/증빙&company=CNH");
            assertThat(upgradeHeaders).containsOnlyNulls();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }    @Test
    void selectingCandidateRelistsWithHttp11AndPersistsWithoutPermissionOrDownload() {
        var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID(), "sa", "", true);
        try {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            var repository = new PerformanceRepository(jdbc);
            var references = new PerformanceDriveFileRepository(jdbc);
            config.setCertificateFolders(List.of("/증빙"));
            var drive = new DriveEvidenceService(adapter, config, references, new DriveFileIndexRepository(jdbc));
            var company = org.mockito.Mockito.mock(com.comhu.bidmonitor.submission.port.CompanyFileSearchPort.class);
            var service = new PerformanceService(repository, new PerformanceTableParser(), company, drive);
            var project = service.create(new PerformanceModels.ProjectInput("선택 테스트", java.time.LocalDate.now()));
            var entry = service.paste(project.id(), new PerformanceModels.PasteInput(
                    "1	한국관광	2024.01 ~ 2025.12	100	발주처", null)).saved().getFirst();
            var ref = references.register(config.getBaseUrl(), "CNH",
                    new FmsDrivePort.Item("한국관광 실적증명원.pdf", "/증빙/한국관광 실적증명원.pdf", false, 3, null));
            var info = entry.info();
            var saved = service.update(project.id(), entry.id(), new PerformanceModels.EntryInput(
                    info.pptNumber(), info.businessName(), info.businessPeriod(), info.contractAmount(), info.client(),
                    info.businessStatus(), null, PerformanceModels.EvidenceType.CERTIFICATE,
                    info.kitcStatus(), info.requestedAt(), info.repliedAt(), ref.id()));
            assertThat(saved.info().selectedDriveFileId()).isEqualTo(ref.id());
            assertThat(saved.selectedFilename()).isEqualTo("한국관광 실적증명원.pdf");
            assertThat(queries).containsExactly("path=/증빙&company=CNH");
            assertThat(upgradeHeaders).containsOnlyNulls();
            assertThat(downloads.get()).isZero();
            org.mockito.Mockito.verifyNoInteractions(company);
        } finally { ds.destroy(); }
    }}
