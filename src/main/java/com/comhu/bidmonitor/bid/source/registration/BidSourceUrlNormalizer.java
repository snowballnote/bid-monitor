package com.comhu.bidmonitor.bid.source.registration;

import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class BidSourceUrlNormalizer {

    public static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "localhost.localdomain");
    private static final ListSuffix BLOCKED_SUFFIXES = new ListSuffix(
            ".localhost", ".local", ".internal", ".home", ".lan"
    );

    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("siteUrl is required.");
        }
        String candidate = value.trim();
        if (candidate.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("siteUrl must not exceed 2048 characters.");
        }

        URI parsed;
        try {
            parsed = new URI(candidate).normalize();
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
        String scheme = parsed.getScheme() == null
                ? null : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("siteUrl must use HTTP or HTTPS.");
        }
        if (parsed.getUserInfo() != null || parsed.getFragment() != null || parsed.getHost() == null) {
            throw invalidUrl();
        }

        String host;
        try {
            host = IDN.toASCII(parsed.getHost(), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || isBlockedHost(host)) {
            throw new IllegalArgumentException("siteUrl must not target a local or private network.");
        }
        int port = parsed.getPort();
        if (port == 0 || port > 65535) {
            throw invalidUrl();
        }
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }

        String rawPath = parsed.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        }
        StringBuilder normalized = new StringBuilder(scheme).append("://").append(host);
        if (port >= 0) {
            normalized.append(':').append(port);
        }
        normalized.append(rawPath);
        if (parsed.getRawQuery() != null) {
            normalized.append('?').append(parsed.getRawQuery());
        }
        String result = normalized.toString();
        if (result.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("siteUrl must not exceed 2048 characters.");
        }
        return result;
    }

    private boolean isBlockedHost(String host) {
        if (BLOCKED_HOSTS.contains(host) || BLOCKED_SUFFIXES.matches(host) || host.indexOf(':') >= 0) {
            return true;
        }
        if (host.chars().allMatch(Character::isDigit) || isHexadecimalAddress(host)) {
            return true;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return host.chars().allMatch(character -> Character.isDigit(character) || character == '.');
        }
        int[] address = new int[4];
        for (int index = 0; index < octets.length; index++) {
            if (octets[index].isEmpty()
                    || (octets[index].length() > 1 && octets[index].startsWith("0"))
                    || !octets[index].chars().allMatch(Character::isDigit)) {
                return true;
            }
            try {
                address[index] = Integer.parseInt(octets[index]);
            } catch (NumberFormatException exception) {
                return true;
            }
            if (address[index] > 255) {
                return true;
            }
        }
        return address[0] == 0
                || address[0] == 10
                || address[0] == 127
                || (address[0] == 100 && address[1] >= 64 && address[1] <= 127)
                || (address[0] == 169 && address[1] == 254)
                || (address[0] == 172 && address[1] >= 16 && address[1] <= 31)
                || (address[0] == 192 && address[1] == 168)
                || (address[0] == 198 && (address[1] == 18 || address[1] == 19))
                || address[0] >= 224;
    }

    private boolean isHexadecimalAddress(String host) {
        if (!host.startsWith("0x") || host.length() == 2) {
            return false;
        }
        return host.substring(2).chars().allMatch(character ->
                Character.digit(character, 16) >= 0
        );
    }

    private IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("siteUrl must be a valid public HTTP or HTTPS URL.");
    }

    private record ListSuffix(String... values) {
        private boolean matches(String host) {
            for (String value : values) {
                if (host.endsWith(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
