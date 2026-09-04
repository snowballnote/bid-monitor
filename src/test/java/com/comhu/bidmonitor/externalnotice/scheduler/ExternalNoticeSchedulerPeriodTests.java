package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionWorkflow;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionWorkflowResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;

import java.util.List;
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
        "external-notice.scheduler.fixed-delay-ms=100",
        "external-notice.scheduler.initial-delay-ms=250",
        "external-notice.scheduler.run-on-startup=false"
})
class ExternalNoticeSchedulerPeriodTests {

    @MockitoBean
    private ExternalNoticeCollectionWorkflow collectionWorkflow;

    @BeforeEach
    void setUpResult() {
        when(collectionWorkflow.run()).thenReturn(new ExternalNoticeCollectionWorkflowResult(
                ExternalNoticeCollectionResult.builder().build(),
                List.of()
        ));
    }

    @Test
    void appliesConfiguredFixedDelay() {
        verify(collectionWorkflow, timeout(2_000).atLeast(2)).run();
    }
}
