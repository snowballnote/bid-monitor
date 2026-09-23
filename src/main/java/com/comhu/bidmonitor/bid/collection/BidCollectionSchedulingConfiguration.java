package com.comhu.bidmonitor.bid.collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bid.collection.scheduler.enabled", havingValue = "true")
@Import(BidCollectionScheduler.class)
public class BidCollectionSchedulingConfiguration {

    @Bean
    BidCollectionSourceSchedule g2bBidCollectionSourceSchedule(
            @Value("${bid.collection.scheduler.g2b.interval-seconds:3600}") long intervalSeconds,
            @Value("${bid.collection.scheduler.g2b.lookback-days:1}") int lookbackDays
    ) {
        return new BidCollectionSourceSchedule("G2B", Duration.ofSeconds(intervalSeconds), lookbackDays);
    }

    @Bean
    BidCollectionSourceSchedule koreaExpresswayBidCollectionSourceSchedule(
            @Value("${bid.collection.scheduler.korea-expressway.interval-seconds:21600}") long intervalSeconds,
            @Value("${bid.collection.scheduler.korea-expressway.lookback-days:7}") int lookbackDays
    ) {
        return new BidCollectionSourceSchedule(
                "KOREA_EXPRESSWAY",
                Duration.ofSeconds(intervalSeconds),
                lookbackDays
        );
    }
}
