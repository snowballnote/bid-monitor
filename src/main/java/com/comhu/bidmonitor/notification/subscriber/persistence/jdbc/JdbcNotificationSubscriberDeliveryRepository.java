package com.comhu.bidmonitor.notification.subscriber.persistence.jdbc;

import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDelivery;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDeliveryRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** DB unique 제약으로 동일 이벤트의 동일 신청자 중복 발송 예약을 원자적으로 차단한다. */
@Repository
public class JdbcNotificationSubscriberDeliveryRepository
        implements NotificationSubscriberDeliveryRepository {

    private static final String COLUMNS = """
            id, notification_delivery_id, subscriber_id, status, created_at, sent_at, failure_reason
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationSubscriberDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<NotificationSubscriberDelivery> createPendingIfAbsent(
            Long notificationDeliveryId,
            Long subscriberId,
            Instant createdAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                                INSERT INTO notification_subscriber_delivery (
                                    notification_delivery_id, subscriber_id, status,
                                    created_at, sent_at, failure_reason
                                ) VALUES (?, ?, ?, ?, NULL, NULL)
                                """,
                        Statement.RETURN_GENERATED_KEYS
                );
                statement.setLong(1, notificationDeliveryId);
                statement.setLong(2, subscriberId);
                statement.setString(3, NotificationDeliveryStatus.PENDING.name());
                statement.setTimestamp(4, Timestamp.from(createdAt));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException ignored) {
            return Optional.empty();
        }

        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("신청자별 발송 기록 ID를 가져올 수 없습니다.");
        }
        return findById(id);
    }

    @Override
    public List<NotificationSubscriberDelivery> findByNotificationDeliveryId(
            Long notificationDeliveryId
    ) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification_subscriber_delivery "
                        + "WHERE notification_delivery_id = ? ORDER BY id",
                this::mapDelivery,
                notificationDeliveryId
        );
    }

    @Override
    public void markSent(Long id, Instant sentAt) {
        requireSingleUpdatedRow(jdbcTemplate.update(
                "UPDATE notification_subscriber_delivery "
                        + "SET status = ?, sent_at = ?, failure_reason = NULL "
                        + "WHERE id = ? AND status = ?",
                NotificationDeliveryStatus.SENT.name(),
                Timestamp.from(sentAt),
                id,
                NotificationDeliveryStatus.PENDING.name()
        ), id);
    }

    @Override
    public void markFailed(Long id, String failureReason) {
        requireSingleUpdatedRow(jdbcTemplate.update(
                "UPDATE notification_subscriber_delivery "
                        + "SET status = ?, sent_at = NULL, failure_reason = ? "
                        + "WHERE id = ? AND status = ?",
                NotificationDeliveryStatus.FAILED.name(),
                failureReason,
                id,
                NotificationDeliveryStatus.PENDING.name()
        ), id);
    }

    private Optional<NotificationSubscriberDelivery> findById(Long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification_subscriber_delivery WHERE id = ?",
                this::mapDelivery,
                id
        ).stream().findFirst();
    }

    private NotificationSubscriberDelivery mapDelivery(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp sentAt = resultSet.getTimestamp("sent_at");
        return NotificationSubscriberDelivery.builder()
                .id(resultSet.getLong("id"))
                .notificationDeliveryId(resultSet.getLong("notification_delivery_id"))
                .subscriberId(resultSet.getLong("subscriber_id"))
                .status(NotificationDeliveryStatus.valueOf(resultSet.getString("status")))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .sentAt(sentAt == null ? null : sentAt.toInstant())
                .failureReason(resultSet.getString("failure_reason"))
                .build();
    }

    private void requireSingleUpdatedRow(int updatedRows, Long id) {
        if (updatedRows != 1) {
            throw new IllegalStateException("갱신할 신청자별 발송 기록을 찾을 수 없습니다: " + id);
        }
    }
}
