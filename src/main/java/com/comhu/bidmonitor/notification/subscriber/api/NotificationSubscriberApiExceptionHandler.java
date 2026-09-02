package com.comhu.bidmonitor.notification.subscriber.api;

import com.comhu.bidmonitor.notification.subscriber.api.dto.NotificationSubscriberErrorResponse;
import com.comhu.bidmonitor.notification.subscriber.service.DuplicateNotificationSubscriberException;
import com.comhu.bidmonitor.notification.subscriber.service.InvalidNotificationSubscriberException;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 신청자 입력·중복·조회 오류를 내부 관리 API에 맞는 HTTP 상태로 변환한다. */
@RestControllerAdvice(assignableTypes = NotificationSubscriberController.class)
public class NotificationSubscriberApiExceptionHandler {

    @ExceptionHandler(InvalidNotificationSubscriberException.class)
    public ResponseEntity<NotificationSubscriberErrorResponse> handleInvalid(
            InvalidNotificationSubscriberException exception
    ) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DuplicateNotificationSubscriberException.class)
    public ResponseEntity<NotificationSubscriberErrorResponse> handleDuplicate(
            DuplicateNotificationSubscriberException exception
    ) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(NotificationSubscriberNotFoundException.class)
    public ResponseEntity<NotificationSubscriberErrorResponse> handleNotFound(
            NotificationSubscriberNotFoundException exception
    ) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private ResponseEntity<NotificationSubscriberErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(NotificationSubscriberErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .build());
    }
}
