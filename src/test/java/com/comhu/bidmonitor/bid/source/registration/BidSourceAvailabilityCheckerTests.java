package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BidSourceAvailabilityCheckerTests {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-28T00:00:00Z");
    private static final InetAddress PUBLIC_ADDRESS = address("93.184.216.34");

    @Test
    void reachesPublicHttpAndDetectsHtml() {
        BidSourceAvailabilityChecker checker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> response(200, "text/html; charset=UTF-8", "<!doctype html><html>")
        );

        BidSourceCheckResult result = checker.check("https://bids.example/notices");
        BidSourceCheckResult httpResult = checker.check("http://bids.example/notices");

        assertEquals(BidSourceRegistration.CheckStatus.REACHABLE, result.checkStatus());
        assertEquals(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, result.detectedCollectionMethod());
        assertEquals(200, result.httpStatus());
        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertEquals(CHECKED_AT, result.checkedAt());
        assertNull(result.safeFailureCode());
        assertEquals(BidSourceRegistration.CheckStatus.REACHABLE, httpResult.checkStatus());
    }

    @Test
    void detectsRssAndOfficialApiHints() {
        BidSourceAvailabilityChecker rssChecker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> response(200, "application/xml", "<rss version=\"2.0\"></rss>")
        );
        BidSourceAvailabilityChecker apiChecker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> response(200, "application/json", "{}")
        );

        assertEquals(BidSourceRegistration.CollectionMethod.RSS,
                rssChecker.check("https://bids.example/feed").detectedCollectionMethod());
        assertEquals(BidSourceRegistration.CollectionMethod.OFFICIAL_API,
                apiChecker.check("https://bids.example/openapi/notices").detectedCollectionMethod());
    }

    @Test
    void blocksLocalhostAndPrivateResolvedAddress() {
        BidSourceAvailabilityChecker checker = checker(
                host -> new InetAddress[]{address("10.20.30.40")},
                (uri, address, limit) -> {
                    throw new AssertionError("Blocked addresses must not reach the transport.");
                }
        );

        assertBlocked(checker.check("http://localhost/notices"));
        assertBlocked(checker.check("https://private.example/notices"));

        BidSourceAvailabilityChecker mixedDnsChecker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS, address("10.20.30.40")},
                (uri, address, limit) -> {
                    throw new AssertionError("Mixed public/private DNS answers must be blocked.");
                }
        );
        assertBlocked(mixedDnsChecker.check("https://mixed.example/notices"));
    }

    @Test
    void blocksRedirectWhenItsResolvedAddressIsPrivate() {
        BidSourceAvailabilityChecker checker = checker(
                host -> new InetAddress[]{host.equals("public.example")
                        ? PUBLIC_ADDRESS : address("192.168.1.10")},
                (uri, address, limit) -> new BidSourceAvailabilityChecker.RawResponse(
                        302,
                        Map.of("location", List.of("http://private.example/admin")),
                        new byte[0]
                )
        );

        assertBlocked(checker.check("https://public.example/notices"));
    }

    @Test
    void limitsRedirects() {
        BidSourceAvailabilityChecker checker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> new BidSourceAvailabilityChecker.RawResponse(
                        302,
                        Map.of("location", List.of("/next")),
                        new byte[0]
                )
        );

        BidSourceCheckResult result = checker.check("https://public.example/start");

        assertEquals(BidSourceRegistration.CheckStatus.UNREACHABLE, result.checkStatus());
        assertEquals(BidSourceRegistration.SafeFailureCode.REDIRECT_LIMIT_EXCEEDED,
                result.safeFailureCode());
    }

    @Test
    void mapsTimeoutAndResponseLimitToSafeFailureCodes() {
        BidSourceAvailabilityChecker timeoutChecker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> {
                    throw new SocketTimeoutException();
                }
        );
        BidSourceAvailabilityChecker largeResponseChecker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> {
                    throw new BidSourceAvailabilityChecker.ResponseTooLargeException();
                }
        );

        assertEquals(BidSourceRegistration.SafeFailureCode.CONNECTION_TIMEOUT,
                timeoutChecker.check("https://public.example/").safeFailureCode());
        assertEquals(BidSourceRegistration.SafeFailureCode.RESPONSE_TOO_LARGE,
                largeResponseChecker.check("https://public.example/").safeFailureCode());
    }

    @Test
    void rejectsUnsupportedContentTypeWithoutReturningBody() {
        BidSourceAvailabilityChecker checker = checker(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> response(200, "application/octet-stream", "secret body")
        );

        BidSourceCheckResult result = checker.check("https://public.example/download");

        assertEquals(BidSourceRegistration.CheckStatus.UNREACHABLE, result.checkStatus());
        assertEquals(BidSourceRegistration.SafeFailureCode.UNSUPPORTED_CONTENT_TYPE,
                result.safeFailureCode());
        assertEquals("application/octet-stream", result.contentType());
    }

    private BidSourceAvailabilityChecker checker(
            BidSourceAvailabilityChecker.HostResolver resolver,
            BidSourceAvailabilityChecker.Transport transport
    ) {
        return new BidSourceAvailabilityChecker(
                resolver,
                transport,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)
        );
    }

    private static BidSourceAvailabilityChecker.RawResponse response(
            int status,
            String contentType,
            String body
    ) {
        return new BidSourceAvailabilityChecker.RawResponse(
                status,
                Map.of("content-type", List.of(contentType)),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private void assertBlocked(BidSourceCheckResult result) {
        assertEquals(BidSourceRegistration.CheckStatus.BLOCKED, result.checkStatus());
        assertEquals(BidSourceRegistration.SafeFailureCode.ADDRESS_BLOCKED, result.safeFailureCode());
        assertNull(result.httpStatus());
    }
}
