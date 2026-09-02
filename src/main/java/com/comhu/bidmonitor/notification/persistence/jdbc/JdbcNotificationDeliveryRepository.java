package com.comhu.bidmonitor.notification.persistence.jdbc;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
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
import java.util.Objects;
import java.util.Optional;

/** DB 유일성 제약을 이용해 동일 내용의 알림 후보를 한 번만 PENDING으로 예약한다. */
@Repository
public class JdbcNotificationDeliveryRepository implements NotificationDeliveryRepository {

    private static final String DELIVERY_COLUMNS = """
            id, channel, notification_type, source_code, external_id, change_type,
            content_fingerprint, status, created_at, sent_at, failure_reason
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<NotificationDelivery> createPendingIfAbsent(NotificationDelivery delivery) {
        Objects.requireNonNull(delivery, "예약할 알림 이력은 null일 수 없습니다.");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                                INSERT INTO notification_delivery (
                                    channel, notification_type, source_code, external_id, change_type,
                                    content_fingerprint, status, created_at, sent_at, failure_reason
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                                """,
                        Statement.RETURN_GENERATED_KEYS
                );
                statement.setString(1, delivery.getChannel().name());
                statement.setString(2, delivery.getNotificationType().name());
                statement.setString(3, delivery.getSourceCode());
                statement.setString(4, delivery.getExternalId());
                statement.setString(5, delivery.getChangeType().name());
                statement.setString(6, delivery.getContentFingerprint());
                statement.setString(7, NotificationDeliveryStatus.PENDING.name());
                statement.setTimestamp(8, Timestamp.from(delivery.getCreatedAt()));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException ignored) {
            return Optional.empty();
        }

        Long deliveryId = keyHolder.getKeyAs(Long.class);
        if (deliveryId == null) {
            throw new IllegalStateException("예약한 알림 이력의 ID를 가져올 수 없습니다.");
        }
        return findById(deliveryId);
    }

    @Override
    public List<NotificationDelivery> findPending(
            NotificationChannel channel,
            NotificationType notificationType
    ) {
        return jdbcTemplate.query(
                "SELECT " + DELIVERY_COLUMNS + " FROM notification_delivery "
                        + "WHERE channel = ? AND notification_type = ? AND status = ? "
                        + "ORDER BY created_at, id",
                this::mapDelivery,
                channel.name(),
                notificationType.name(),
                NotificationDeliveryStatus.PENDING.name()
        );
    }

    @Override
    public void markSent(Long deliveryId, Instant sentAt) {
        int updatedRows = jdbcTemplate.update(
                """
                        UPDATE notification_delivery
                        SET status = ?, sent_at = ?, failure_reason = NULL
                        WHERE id = ? AND status = ?
                        """,
                NotificationDeliveryStatus.SENT.name(),
                Timestamp.from(sentAt),
                deliveryId,
                NotificationDeliveryStatus.PENDING.name()
        );
        requireSingleUpdatedRow(updatedRows, deliveryId);
    }

    @Override
    public void markFailed(Long deliveryId, String failureReason) {
        int updatedRows = jdbcTemplate.update(
                """
                        UPDATE notification_delivery
                        SET status = ?, sent_at = NULL, failure_reason = ?
                        WHERE id = ? AND status = ?
                        """,
                NotificationDeliveryStatus.FAILED.name(),
                failureReason,
                deliveryId,
                NotificationDeliveryStatus.PENDING.name()
        );
        requireSingleUpdatedRow(updatedRows, deliveryId);
    }

    @Override
    public List<NotificationDelivery> findAll() {
        return jdbcTemplate.query(
                "SELECT " + DELIVERY_COLUMNS + " FROM notification_delivery ORDER BY id",
                this::mapDelivery
        );
    }

    private Optional<NotificationDelivery> findById(Long deliveryId) {
        return jdbcTemplate.query(
                "SELECT " + DELIVERY_COLUMNS + " FROM notification_delivery WHERE id = ?",
                this::mapDelivery,
                deliveryId
        ).stream().findFirst();
    }

    private NotificationDelivery mapDelivery(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp sentAt = resultSet.getTimestamp("sent_at");
        return NotificationDelivery.builder()
                .id(resultSet.getLong("id"))
                .channel(NotificationChannel.valueOf(resultSet.getString("channel")))
                .notificationType(NotificationType.valueOf(resultSet.getString("notification_type")))
                .sourceCode(resultSet.getString("source_code"))
                .externalId(resultSet.getString("external_id"))
                .changeType(NoticeChangeType.valueOf(resultSet.getString("change_type")))
                .contentFingerprint(resultSet.getString("content_fingerprint"))
                .status(NotificationDeliveryStatus.valueOf(resultSet.getString("status")))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .sentAt(sentAt == null ? null : sentAt.toInstant())
                .failureReason(resultSet.getString("failure_reason"))
                .build();
    }

    private void requireSingleUpdatedRow(int updatedRows, Long deliveryId) {
        if (updatedRows != 1) {
            throw new IllegalStateException("갱신할 알림 이력 한 건을 찾을 수 없습니다: " + deliveryId);
        }
    }
}
