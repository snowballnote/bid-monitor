package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BidCollectionSchedulerContextTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ManualBidCollectionCoordinator.class, () -> mock(ManualBidCollectionCoordinator.class))
            .withBean(ManualBidCollectionSourceRegistry.class, () -> mock(ManualBidCollectionSourceRegistry.class))
            .withBean(BidSourceStateRepository.class, () -> mock(BidSourceStateRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(BidCollectionSchedulingConfiguration.class);

    @Test
    void schedulerIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(BidCollectionScheduler.class));
    }

    @Test
    void schedulerCanBeEnabledExplicitly() {
        contextRunner
                .withPropertyValues(
                        "bid.collection.scheduler.enabled=true",
                        "bid.collection.scheduler.initial-delay-ms=7200000",
                        "bid.collection.scheduler.scan-interval-ms=7200000",
                        "bid.collection.scheduler.kogas.interval-seconds=43200",
                        "bid.collection.scheduler.kogas.lookback-days=3",
                        "bid.collection.scheduler.generic.interval-seconds=1800",
                        "bid.collection.scheduler.generic.lookback-days=2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(BidCollectionScheduler.class);
                    assertThat(context).getBeans(BidCollectionSourceSchedule.class).hasSize(3);
                    assertThat(context).hasSingleBean(GenericPublicPageSchedule.class);
                    assertThat(context.getBean(GenericPublicPageSchedule.class)).isEqualTo(
                            new GenericPublicPageSchedule(java.time.Duration.ofMinutes(30), 2)
                    );
                    assertThat(context.getBean(
                            "kogasBidCollectionSourceSchedule",
                            BidCollectionSourceSchedule.class
                    )).isEqualTo(new BidCollectionSourceSchedule(
                            "KOGAS", java.time.Duration.ofHours(12), 3
                    ));
                });
    }

    @Test
    void genericScheduleUsesConservativeDefaults() {
        contextRunner
                .withPropertyValues(
                        "bid.collection.scheduler.enabled=true",
                        "bid.collection.scheduler.initial-delay-ms=7200000",
                        "bid.collection.scheduler.scan-interval-ms=7200000"
                )
                .run(context -> assertThat(context.getBean(GenericPublicPageSchedule.class))
                        .isEqualTo(new GenericPublicPageSchedule(java.time.Duration.ofHours(1), 3)));
    }
}
