package com.comhu.bidmonitor.externalnotice.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 외부공지 자동 수집이 활성화된 경우에만 Scheduler와 Spring Scheduling을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(ExternalNoticeScheduler.class)
@ConditionalOnProperty(
        prefix = "external-notice.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ExternalNoticeSchedulingConfiguration {
}
