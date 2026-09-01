package com.comhu.bidmonitor.externalnotice.api;

import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 외부공지 API의 조회 오류를 명확한 HTTP 상태와 JSON 메시지로 변환한다. */
@RestControllerAdvice(assignableTypes = ExternalNoticeController.class)
public class ExternalNoticeApiExceptionHandler {

    @ExceptionHandler(ExternalNoticeNotFoundException.class)
    public ResponseEntity<ExternalNoticeErrorResponse> handleNotFound(ExternalNoticeNotFoundException exception) {
        ExternalNoticeErrorResponse response = ExternalNoticeErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(exception.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
}
