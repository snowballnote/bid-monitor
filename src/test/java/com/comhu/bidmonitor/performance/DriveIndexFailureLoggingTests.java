package com.comhu.bidmonitor.performance;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriveIndexFailureLoggingTests {
    @Test
    void logsOriginalTypesAndStacksWithoutMessagesCausesOrSuppressedSecrets() {
        var logger = (Logger) LoggerFactory.getLogger(DriveFileIndexRefreshService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var config = new FmsDriveProperties();
            config.setBaseUrl("http://private-host");
            config.setSessionToken("private-session");
            config.setCertificateFolders(List.of("/private-root"));
            var drive = mock(FmsDrivePort.class);
            var index = mock(DriveFileIndexRepository.class);
            var service = new DriveFileIndexRefreshService(drive, config, index);
            var original = new IllegalStateException("SESSION=private-session password=secret /private-root",
                    new java.net.ConnectException("private-host credential"));
            original.addSuppressed(new RuntimeException("suppressed-secret"));
            when(drive.list("/private-root")).thenThrow(original);
            service.refresh();
            verify(index).failed("http://private-host", "CNH", "/private-root");
            verify(index, never()).replace(any(), any(), any(), any(), anyInt());
            var event = appender.list.getFirst();
            String output = event.getFormattedMessage() + ThrowableProxyUtil.asString(event.getThrowableProxy());
            assertThat(output).contains("stage=LIST", "java.lang.IllegalStateException", "java.net.ConnectException",
                    "DriveIndexFailureLoggingTests");
            assertThat(output).doesNotContain("private-root", "private-session", "private-host",
                    "password", "credential", "suppressed-secret", "SESSION");
            appender.list.clear();
            config.setCertificateFolders(List.of("/private-root/../secret"));
            assertThatThrownBy(service::refresh).isInstanceOf(FmsDriveException.class);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("kind=INVALID_ROOT", "stage=VALIDATION")
                    .doesNotContain("private-root");
            appender.list.clear();
            config.setCertificateFolders(List.of());
            assertThatThrownBy(service::refresh).isInstanceOf(FmsDriveException.class);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("kind=INVALID_CONFIG", "stage=CONFIG");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void refreshLogIncludesHttpStatusWithoutResponseDetails() {
        var logger = (Logger) LoggerFactory.getLogger(DriveFileIndexRefreshService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var config = new FmsDriveProperties();
            config.setCertificateFolders(List.of("/private"));
            var port = mock(FmsDrivePort.class);
            var index = mock(DriveFileIndexRepository.class);
            for (int status : List.of(401, 403, 404)) {
                doThrow(new FmsDriveException("secret").details(FmsDriveException.Stage.LIST,
                        FmsDriveException.Kind.HTTP_ERROR, status, null)).when(port).list("/private");
                new DriveFileIndexRefreshService(port, config, index).refresh();
                var event = appender.list.getLast();
                assertThat(event.getFormattedMessage()).contains("stage=LIST", "httpStatus=" + status);
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain("secret", "/private");
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}