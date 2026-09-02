package com.comhu.bidmonitor.notification.subscriber.persistence;

import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 신청자 관리와 dispatcher의 활성 수신자 조회를 DB 기술에서 분리하는 계약이다. */
public interface NotificationSubscriberRepository {

    NotificationSubscriber save(NotificationSubscriber subscriber);

    Optional<NotificationSubscriber> findById(Long id);

    List<NotificationSubscriber> findAll();

    List<NotificationSubscriber> findEnabledByNotificationType(NotificationType notificationType);

    boolean disable(Long id, Instant updatedAt);
}
