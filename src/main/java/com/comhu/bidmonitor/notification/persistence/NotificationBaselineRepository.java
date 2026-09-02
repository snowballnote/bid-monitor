package com.comhu.bidmonitor.notification.persistence;

import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationType;

import java.time.Instant;

/** 출처별 최초 자동 수집이 완료됐는지 영속적으로 예약·조회한다. */
public interface NotificationBaselineRepository {

    boolean completeIfAbsent(
            NotificationChannel channel,
            NotificationType notificationType,
            String sourceCode,
            Instant completedAt
    );

    boolean isCompleted(
            NotificationChannel channel,
            NotificationType notificationType,
            String sourceCode
    );
}
