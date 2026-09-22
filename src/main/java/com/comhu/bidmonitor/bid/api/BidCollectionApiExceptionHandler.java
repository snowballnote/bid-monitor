package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.api.dto.BidApiErrorResponse;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionResponse;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionException;
import com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BidCollectionController.class)
public class BidCollectionApiExceptionHandler {

    private static final String ALREADY_RUNNING = "ALREADY_RUNNING";
    private static final String DAILY_QUOTA_EXCEEDED = "DAILY_QUOTA_EXCEEDED";

    @ExceptionHandler(ManualBidCollectionException.class)
    public ResponseEntity<BidCollectionResponse> handleCollectionFailure(
            ManualBidCollectionException exception
    ) {
        boolean conflict = allFailuresHaveCode(exception, ALREADY_RUNNING);
        boolean quotaExceeded = allFailuresHaveCode(exception, DAILY_QUOTA_EXCEEDED);
        HttpStatus status = conflict
                ? HttpStatus.CONFLICT
                : quotaExceeded ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(BidCollectionResponse.from(exception.getResult()));
    }

    @ExceptionHandler(BidCollectionRunNotFoundException.class)
    public ResponseEntity<BidApiErrorResponse> handleNotFound() {
        return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Bid collection run was not found.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BidApiErrorResponse> handleInvalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BidApiErrorResponse> handleMalformed() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body and date format must be valid.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BidApiErrorResponse> handleUnavailable() {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "COLLECTION_UNAVAILABLE",
                "Bid collection request could not be completed."
        );
    }

    private boolean allFailuresHaveCode(ManualBidCollectionException exception, String errorCode) {
        return !exception.getResult().sourceResults().isEmpty()
                && exception.getResult().sourceResults().stream()
                .filter(result -> result.status() == BidSourcePersistenceResult.Status.FAILED)
                .allMatch(result -> errorCode.equals(result.errorCode()));
    }

    private ResponseEntity<BidApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new BidApiErrorResponse(
                status.value(), status.getReasonPhrase(), code, message
        ));
    }
}
