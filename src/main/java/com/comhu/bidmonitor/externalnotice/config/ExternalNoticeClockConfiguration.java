package com.comhu.bidmonitor.externalnotice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 운영에서는 UTC 시스템 시계를 사용하고 테스트에서는 고정 Clock으로 교체할 수 있게 한다. */
@Configuration(proxyBeanMethods = false)
public class ExternalNoticeClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock externalNoticeClock() {
        return Clock.systemUTC();
    }
}
