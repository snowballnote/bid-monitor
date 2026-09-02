package com.comhu.bidmonitor.notification.subscriber.api;

import com.comhu.bidmonitor.notification.subscriber.api.dto.CreateNotificationSubscriberRequest;
import com.comhu.bidmonitor.notification.subscriber.api.dto.NotificationSubscriberResponse;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 로그인 도입 전 내부 운영자가 신청자를 등록·조회·비활성화하는 최소 관리 API다. */
@RestController
@RequestMapping("/api/notification-subscribers")
public class NotificationSubscriberController {

    private final NotificationSubscriberService service;

    public NotificationSubscriberController(NotificationSubscriberService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<NotificationSubscriberResponse> register(
            @RequestBody CreateNotificationSubscriberRequest request
    ) {
        NotificationSubscriberResponse response = NotificationSubscriberResponse.from(service.register(
                request.getEmail(),
                request.getName(),
                request.getNotificationType()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<NotificationSubscriberResponse> findAll() {
        return service.findAll().stream().map(NotificationSubscriberResponse::from).toList();
    }

    @PatchMapping("/{id}/disable")
    public NotificationSubscriberResponse disable(@PathVariable Long id) {
        return NotificationSubscriberResponse.from(service.disable(id));
    }
}
