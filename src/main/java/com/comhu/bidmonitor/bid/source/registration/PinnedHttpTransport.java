package com.comhu.bidmonitor.bid.source.registration;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PinnedHttpTransport implements BidSourceAvailabilityChecker.Transport {

    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = (int) Duration.ofSeconds(5).toMillis();
    private static final int READ_TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();

    @Override
    public BidSourceAvailabilityChecker.RawResponse get(
            URI uri,
            InetAddress address,
            int maxResponseBytes
    ) throws IOException {
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        try (Socket socket = openSocket(uri, address, port)) {
            writeRequest(socket.getOutputStream(), uri, port);
            InputStream input = socket.getInputStream();
            String headerBlock = readHeaderBlock(input);
            String[] lines = headerBlock.split("\\r\\n");
            if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
            int statusCode = Integer.parseInt(lines[0].substring(9, 12));
            Map<String, List<String>> headers = parseHeaders(lines);
            byte[] body = readBody(input, headers, maxResponseBytes);
            return new BidSourceAvailabilityChecker.RawResponse(statusCode, headers, body);
        }
    }

    private Socket openSocket(URI uri, InetAddress address, int port) throws IOException {
        Socket connected = new Socket();
        connected.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
        connected.setSoTimeout(READ_TIMEOUT_MILLIS);
        if (!"https".equals(uri.getScheme())) {
            return connected;
        }
        try {
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(connected, uri.getHost(), port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException exception) {
            try {
                connected.close();
            } catch (IOException ignored) {
                // The original connection failure is more useful to the safe failure mapper.
            }
            throw exception;
        }
    }

    private void writeRequest(OutputStream output, URI uri, int port) throws IOException {
        String target = uri.getRawPath();
        if (uri.getRawQuery() != null) {
            target += "?" + uri.getRawQuery();
        }
        boolean defaultPort = "https".equals(uri.getScheme()) ? port == 443 : port == 80;
        String hostHeader = uri.getHost() + (defaultPort ? "" : ":" + port);
        String request = "GET " + target + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: BizAssist-SiteCheck/1.0\r\n"
                + "Accept: text/html, application/json, application/xml, text/xml, application/rss+xml, application/atom+xml\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private String readHeaderBlock(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() <= MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
    }

    private Map<String, List<String>> parseHeaders(String[] lines) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator < 1) {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
            String name = lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = lines[index].substring(separator + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    private byte[] readBody(
            InputStream input,
            Map<String, List<String>> headers,
            int maxResponseBytes
    ) throws IOException {
        String transferEncoding = firstHeader(headers, "transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(input, maxResponseBytes);
        }
        String contentLength = firstHeader(headers, "content-length");
        if (contentLength != null) {
            long length;
            try {
                length = Long.parseLong(contentLength);
            } catch (NumberFormatException exception) {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
            if (length < 0 || length > maxResponseBytes) {
                throw new BidSourceAvailabilityChecker.ResponseTooLargeException();
            }
            return readExact(input, (int) length);
        }
        return readBounded(input, maxResponseBytes);
    }

    private byte[] readChunked(InputStream input, int maxResponseBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(input);
            int extension = line.indexOf(';');
            String sizeText = (extension >= 0 ? line.substring(0, extension) : line).trim();
            long size;
            try {
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException exception) {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
            if (size < 0 || size > maxResponseBytes - body.size()) {
                throw new BidSourceAvailabilityChecker.ResponseTooLargeException();
            }
            if (size == 0) {
                return body.toByteArray();
            }
            body.write(readExact(input, (int) size));
            if (input.read() != '\r' || input.read() != '\n') {
                throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
            }
        }
    }

    private String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
                }
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            bytes.write(value);
        }
        throw new BidSourceAvailabilityChecker.InvalidHttpResponseException();
    }

    private byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return bytes;
    }

    private byte[] readBounded(InputStream input, int maxResponseBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxResponseBytes + 1);
        if (bytes.length > maxResponseBytes) {
            throw new BidSourceAvailabilityChecker.ResponseTooLargeException();
        }
        return bytes;
    }

    private String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
