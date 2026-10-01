package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class BidSourceAvailabilityChecker {

    static final int MAX_REDIRECTS = 5;
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final HostResolver resolver;
    private final Transport transport;
    private final Clock clock;

    @Autowired
    public BidSourceAvailabilityChecker(Clock clock) {
        this(InetAddress::getAllByName, new PinnedHttpTransport(), clock);
    }

    BidSourceAvailabilityChecker(HostResolver resolver, Transport transport, Clock clock) {
        this.resolver = resolver;
        this.transport = transport;
        this.clock = clock;
    }

    public BidSourceCheckResult check(String siteUrl) {
        FetchResult fetched = fetch(siteUrl);
        if (fetched.failureCode() != null) {
            return fetched.blocked()
                    ? blocked(fetched.failureCode())
                    : failed(fetched.failureCode(), fetched.statusCode(), fetched.contentType());
        }
        BidSourceRegistration.CollectionMethod detected = detectCollectionMethod(
                fetched.uri(), fetched.contentType(), fetched.body()
        );
        return new BidSourceCheckResult(
                BidSourceRegistration.CheckStatus.REACHABLE,
                detected,
                fetched.statusCode(),
                fetched.contentType(),
                clock.instant(),
                null
        );
    }

    FetchResult fetch(String siteUrl) {
        return fetch(siteUrl, ignored -> true);
    }

    FetchResult fetch(String siteUrl, Predicate<URI> allowedTarget) {
        Objects.requireNonNull(allowedTarget, "allowedTarget is required.");
        URI current;
        try {
            current = new URI(siteUrl);
        } catch (URISyntaxException | NullPointerException exception) {
            return FetchResult.blocked(BidSourceRegistration.SafeFailureCode.INVALID_URL);
        }

        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            try {
                if (!allowedTarget.test(current)) {
                    return redirects == 0
                            ? FetchResult.blocked(BidSourceRegistration.SafeFailureCode.INVALID_URL)
                            : FetchResult.failed(BidSourceRegistration.SafeFailureCode.INVALID_REDIRECT,
                            null, null);
                }
                ValidatedTarget target = validateAndResolve(current);
                RawResponse response = transport.get(target.uri(), target.address(), MAX_RESPONSE_BYTES);
                String contentType = safeContentType(response.headers());

                if (REDIRECT_STATUSES.contains(response.statusCode())) {
                    if (redirects == MAX_REDIRECTS) {
                        return FetchResult.failed(BidSourceRegistration.SafeFailureCode.REDIRECT_LIMIT_EXCEEDED,
                                response.statusCode(), contentType);
                    }
                    String location = firstHeader(response.headers(), "location");
                    if (location == null || location.isBlank()) {
                        return FetchResult.failed(BidSourceRegistration.SafeFailureCode.INVALID_REDIRECT,
                                response.statusCode(), contentType);
                    }
                    try {
                        current = current.resolve(new URI(location));
                    } catch (IllegalArgumentException | URISyntaxException exception) {
                        return FetchResult.failed(BidSourceRegistration.SafeFailureCode.INVALID_REDIRECT,
                                response.statusCode(), contentType);
                    }
                    continue;
                }

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return FetchResult.failed(BidSourceRegistration.SafeFailureCode.HTTP_ERROR,
                            response.statusCode(), contentType);
                }
                if (!isSupportedContentType(contentType)) {
                    return FetchResult.failed(BidSourceRegistration.SafeFailureCode.UNSUPPORTED_CONTENT_TYPE,
                            response.statusCode(), contentType);
                }
                return FetchResult.success(current, response.statusCode(), contentType, response.body());
            } catch (AddressBlockedException exception) {
                return FetchResult.blocked(BidSourceRegistration.SafeFailureCode.ADDRESS_BLOCKED);
            } catch (InvalidTargetException exception) {
                return FetchResult.blocked(BidSourceRegistration.SafeFailureCode.INVALID_URL);
            } catch (UnknownHostException exception) {
                return FetchResult.failed(BidSourceRegistration.SafeFailureCode.DNS_FAILURE, null, null);
            } catch (SocketTimeoutException exception) {
                return FetchResult.failed(BidSourceRegistration.SafeFailureCode.CONNECTION_TIMEOUT, null, null);
            } catch (ResponseTooLargeException exception) {
                return FetchResult.failed(BidSourceRegistration.SafeFailureCode.RESPONSE_TOO_LARGE, null, null);
            } catch (InvalidHttpResponseException exception) {
                return FetchResult.failed(BidSourceRegistration.SafeFailureCode.INVALID_RESPONSE, null, null);
            } catch (IOException exception) {
                return FetchResult.failed(BidSourceRegistration.SafeFailureCode.CONNECTION_FAILED, null, null);
            }
        }
        return FetchResult.failed(BidSourceRegistration.SafeFailureCode.REDIRECT_LIMIT_EXCEEDED, null, null);
    }

    public BidSourceCheckResult failed(BidSourceRegistration.SafeFailureCode code) {
        return failed(code, null, null);
    }

    private BidSourceCheckResult failed(
            BidSourceRegistration.SafeFailureCode code,
            Integer httpStatus,
            String contentType
    ) {
        return new BidSourceCheckResult(
                BidSourceRegistration.CheckStatus.UNREACHABLE,
                BidSourceRegistration.CollectionMethod.UNDETERMINED,
                httpStatus,
                contentType,
                clock.instant(),
                code
        );
    }

    private BidSourceCheckResult blocked(BidSourceRegistration.SafeFailureCode code) {
        return new BidSourceCheckResult(
                BidSourceRegistration.CheckStatus.BLOCKED,
                BidSourceRegistration.CollectionMethod.UNDETERMINED,
                null,
                null,
                clock.instant(),
                code
        );
    }

    private ValidatedTarget validateAndResolve(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new InvalidTargetException();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new InvalidTargetException();
        }

        String host;
        try {
            host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTargetException();
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".home") || host.endsWith(".lan")) {
            throw new AddressBlockedException();
        }

        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new AddressBlockedException();
            }
        }
        URI normalized;
        try {
            normalized = new URI(
                    scheme,
                    null,
                    host,
                    port,
                    uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath(),
                    uri.getRawQuery(),
                    null
            );
        } catch (URISyntaxException exception) {
            throw new InvalidTargetException();
        }
        return new ValidatedTarget(normalized, addresses[0]);
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            boolean transition = (first == 0x20 && second == 0x02)
                    || (first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0 && Byte.toUnsignedInt(bytes[3]) == 0)
                    || isNat64(bytes);
            return !uniqueLocal && !documentation && !transition && (first & 0xe0) == 0x20;
        }
        return false;
    }

    private static boolean isNat64(byte[] bytes) {
        byte[] wellKnown = {0x00, 0x64, (byte) 0xff, (byte) 0x9b};
        return Arrays.equals(Arrays.copyOf(bytes, 4), wellKnown);
    }

    private BidSourceRegistration.CollectionMethod detectCollectionMethod(
            URI uri,
            String contentType,
            byte[] body
    ) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String prefix = new String(body, 0, Math.min(body.length, 4096), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        if (type.contains("rss") || type.contains("atom") || prefix.startsWith("<rss")
                || prefix.startsWith("<feed") || prefix.contains("<rss ")) {
            return BidSourceRegistration.CollectionMethod.RSS;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        boolean apiHint = path.equals("/api") || path.contains("/api/") || path.contains("openapi")
                || path.contains("/rest/");
        if (apiHint && (type.contains("json") || type.contains("xml"))) {
            return BidSourceRegistration.CollectionMethod.OFFICIAL_API;
        }
        if (type.contains("html") || prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) {
            return BidSourceRegistration.CollectionMethod.PUBLIC_PAGE;
        }
        return BidSourceRegistration.CollectionMethod.UNDETERMINED;
    }

    private boolean isSupportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.contains("html") || type.contains("xml")
                || type.contains("json") || type.contains("rss") || type.contains("atom");
    }

    private String safeContentType(Map<String, List<String>> headers) {
        String value = firstHeader(headers, "content-type");
        if (value == null) {
            return null;
        }
        value = value.replace("\r", "").replace("\n", "").trim();
        return value.substring(0, Math.min(value.length(), 255));
    }

    private String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    interface Transport {
        RawResponse get(URI uri, InetAddress address, int maxResponseBytes) throws IOException;
    }

    record RawResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    }

    record FetchResult(
            URI uri,
            Integer statusCode,
            String contentType,
            byte[] body,
            BidSourceRegistration.SafeFailureCode failureCode,
            boolean blocked
    ) {
        static FetchResult success(URI uri, int statusCode, String contentType, byte[] body) {
            return new FetchResult(uri, statusCode, contentType, body.clone(), null, false);
        }

        static FetchResult failed(
                BidSourceRegistration.SafeFailureCode code,
                Integer statusCode,
                String contentType
        ) {
            return new FetchResult(null, statusCode, contentType, new byte[0], code, false);
        }

        static FetchResult blocked(BidSourceRegistration.SafeFailureCode code) {
            return new FetchResult(null, null, null, new byte[0], code, true);
        }
    }

    private record ValidatedTarget(URI uri, InetAddress address) {
    }

    static final class ResponseTooLargeException extends IOException {
    }

    static final class InvalidHttpResponseException extends IOException {
    }

    private static final class InvalidTargetException extends IOException {
    }

    private static final class AddressBlockedException extends IOException {
    }
}
