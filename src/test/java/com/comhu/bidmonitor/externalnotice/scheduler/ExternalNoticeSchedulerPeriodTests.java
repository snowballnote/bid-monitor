package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-scheduler-period;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=true",
        "external-notice.scheduler.fixed-delay=100",
        "external-notice.scheduler.initial-delay=250",
        "external-notice.scheduler.run-on-startup=false"
})
class ExternalNoticeSchedulerPeriodTests {

    @MockitoBean
    private ExternalNoticeCollectionService collectionService;

    @BeforeEach
    void setUpResult() {
        when(collectionService.runCollection()).thenReturn(ExternalNoticeCollectionResult.builder().build());
    }

    @Test
    void appliesConfiguredFixedDelay() {
        verify(collectionService, timeout(2_000).atLeast(2)).runCollection();
    }
}
