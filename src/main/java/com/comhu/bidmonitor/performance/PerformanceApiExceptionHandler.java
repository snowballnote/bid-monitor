package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {PerformanceController.class, DriveFileIndexController.class})
public class PerformanceApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException exception) { return error(400, exception.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return error(400, "입력 형식과 날짜를 확인하세요."); }
    @ExceptionHandler(PerformanceNotFoundException.class)
    ResponseEntity<?> missing() { return error(404, "프로젝트 또는 실적을 찾을 수 없습니다."); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() { return error(409, "이미 저장된 PPT 번호입니다."); }
    @ExceptionHandler({CompanyDatabaseUnavailableException.class, DataAccessException.class})
    ResponseEntity<?> unavailable() { return error(503, "데이터 조회 또는 저장을 완료하지 못했습니다. 연결 상태를 확인하세요."); }
    @ExceptionHandler(FmsDriveException.class)
    ResponseEntity<?> driveFailure(FmsDriveException exception) {
        return error(exception.forbidden() ? 403 : 503, exception.getMessage());
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadTooLarge() { return error(413, "20MB 이하 파일을 선택하세요."); }
    @ExceptionHandler(java.io.UncheckedIOException.class)
    ResponseEntity<?> uploadFailed() { return error(503, "파일을 저장하지 못했습니다. 자체 저장소 상태를 확인하세요."); }
    @ExceptionHandler(IOException.class)
    ResponseEntity<?> downloadFailed() { return error(503, "ZIP을 만들 수 없습니다. 파일 상태·NAS 연결·원본 합계 100MB 제한을 확인하세요."); }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}