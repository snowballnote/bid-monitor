package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeAutomaticCollectionWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalNoticeSchedulerContextTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    ExternalNoticeAutomaticCollectionWorkflow.class,
                    () -> mock(ExternalNoticeAutomaticCollectionWorkflow.class)
            )
            .withUserConfiguration(ExternalNoticeSchedulingConfiguration.class);

    @Test
    void createsSchedulerWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "external-notice.scheduler.enabled=true",
                        "external-notice.scheduler.fixed-delay=7200000",
                        "external-notice.scheduler.initial-delay=7200000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ExternalNoticeScheduler.class);
                    assertThat(context.getEnvironment().getProperty(
                            "external-notice.scheduler.fixed-delay",
                            Long.class
                    )).isEqualTo(7_200_000L);
                });
    }

    @Test
    void doesNotCreateSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("external-notice.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ExternalNoticeScheduler.class));
    }
}
