package com.comhu.bidmonitor.notification.persistence.jdbc;

import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationBaselineRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** 애플리케이션 재시작과 무관하게 출처별 자동 수집 baseline 완료 상태를 보관한다. */
@Repository
public class JdbcNotificationBaselineRepository implements NotificationBaselineRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationBaselineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean completeIfAbsent(
            NotificationChannel channel,
            NotificationType notificationType,
            String sourceCode,
            Instant completedAt
    ) {
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO notification_baseline (
                                channel, notification_type, source_code, completed_at
                            ) VALUES (?, ?, ?, ?)
                            """,
                    channel.name(),
                    notificationType.name(),
                    sourceCode,
                    Timestamp.from(completedAt)
            );
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public boolean isCompleted(
            NotificationChannel channel,
            NotificationType notificationType,
            String sourceCode
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM notification_baseline
                        WHERE channel = ? AND notification_type = ? AND source_code = ?
                        """,
                Long.class,
                channel.name(),
                notificationType.name(),
                sourceCode
        );
        return count != null && count > 0;
    }
}
