package com.comhu.bidmonitor.notification.subscriber.persistence.jdbc;

import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberRepository;
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

/** notification_subscriber 테이블을 Spring JDBC로 관리한다. */
@Repository
public class JdbcNotificationSubscriberRepository implements NotificationSubscriberRepository {

    private static final String COLUMNS = """
            id, email, name, notification_type, enabled, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationSubscriberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public NotificationSubscriber save(NotificationSubscriber subscriber) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO notification_subscriber (
                                email, name, notification_type, enabled, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, subscriber.getEmail());
            statement.setString(2, subscriber.getName());
            statement.setString(3, subscriber.getNotificationType().name());
            statement.setBoolean(4, subscriber.isEnabled());
            statement.setTimestamp(5, Timestamp.from(subscriber.getCreatedAt()));
            statement.setTimestamp(6, Timestamp.from(subscriber.getUpdatedAt()));
            return statement;
        }, keyHolder);

        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("저장된 알림 신청자 ID를 가져올 수 없습니다.");
        }
        return findById(id).orElseThrow(() ->
                new IllegalStateException("저장된 알림 신청자를 다시 조회할 수 없습니다."));
    }

    @Override
    public Optional<NotificationSubscriber> findById(Long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification_subscriber WHERE id = ?",
                this::mapSubscriber,
                id
        ).stream().findFirst();
    }

    @Override
    public List<NotificationSubscriber> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification_subscriber ORDER BY id",
                this::mapSubscriber
        );
    }

    @Override
    public List<NotificationSubscriber> findEnabledByNotificationType(NotificationType notificationType) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification_subscriber "
                        + "WHERE notification_type = ? AND enabled = TRUE ORDER BY id",
                this::mapSubscriber,
                notificationType.name()
        );
    }

    @Override
    public boolean disable(Long id, Instant updatedAt) {
        return jdbcTemplate.update(
                "UPDATE notification_subscriber SET enabled = FALSE, updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt),
                id
        ) == 1;
    }

    private NotificationSubscriber mapSubscriber(ResultSet resultSet, int rowNumber) throws SQLException {
        return NotificationSubscriber.builder()
                .id(resultSet.getLong("id"))
                .email(resultSet.getString("email"))
                .name(resultSet.getString("name"))
                .notificationType(NotificationType.valueOf(resultSet.getString("notification_type")))
                .enabled(resultSet.getBoolean("enabled"))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .updatedAt(resultSet.getTimestamp("updated_at").toInstant())
                .build();
    }
}
