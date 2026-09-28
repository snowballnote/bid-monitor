package com.comhu.bidmonitor.bid.source.registration;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PinnedHttpTransportTests {

    @Test
    void rejectsDeclaredResponseLargerThanLimitUsingFixtureServer() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> fixture = executor.submit(() -> serveOversizedResponse(server));
            URI uri = URI.create("http://fixture.example:" + server.getLocalPort() + "/notices");

            assertThrows(
                    BidSourceAvailabilityChecker.ResponseTooLargeException.class,
                    () -> new PinnedHttpTransport().get(uri, loopback, 16)
            );
            fixture.get(5, TimeUnit.SECONDS);
        }
    }

    private void serveOversizedResponse(ServerSocket server) {
        try (Socket socket = server.accept()) {
            int matched = 0;
            while (matched < 4) {
                int value = socket.getInputStream().read();
                if (value < 0) {
                    throw new java.io.EOFException();
                }
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    case 3 -> value == '\n' ? 4 : 0;
                    default -> matched;
                };
            }
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html\r\n"
                    + "Content-Length: 17\r\n"
                    + "Connection: close\r\n\r\n"
                    + "0123456789abcdefg").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
