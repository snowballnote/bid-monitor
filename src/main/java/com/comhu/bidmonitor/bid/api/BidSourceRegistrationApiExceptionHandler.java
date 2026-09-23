package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.api.dto.BidApiErrorResponse;
import com.comhu.bidmonitor.bid.source.registration.BidSourceRegistrationNotFoundException;
import com.comhu.bidmonitor.bid.source.registration.DuplicateBidSourceUrlException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BidSourceRegistrationController.class)
public class BidSourceRegistrationApiExceptionHandler {

    @ExceptionHandler(DuplicateBidSourceUrlException.class)
    public ResponseEntity<BidApiErrorResponse> handleDuplicate() {
        return error(
                HttpStatus.CONFLICT,
                "SOURCE_URL_ALREADY_REGISTERED",
                "The bid source URL is already registered."
        );
    }

    @ExceptionHandler(BidSourceRegistrationNotFoundException.class)
    public ResponseEntity<BidApiErrorResponse> handleNotFound() {
        return error(
                HttpStatus.NOT_FOUND,
                "SOURCE_REGISTRATION_NOT_FOUND",
                "The bid source registration was not found."
        );
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<BidApiErrorResponse> handleInvalid(Exception exception) {
        String message = exception instanceof IllegalArgumentException
                ? exception.getMessage() : "Request body must be valid.";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BidApiErrorResponse> handleUnavailable() {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "REGISTRATION_UNAVAILABLE",
                "Bid source registration could not be completed."
        );
    }

    private ResponseEntity<BidApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new BidApiErrorResponse(
                status.value(), status.getReasonPhrase(), code, message
        ));
    }
}
