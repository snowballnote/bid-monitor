package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.api.dto.SubmissionErrorResponse;
import com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException;
import com.comhu.bidmonitor.submission.service.InvalidSubmissionSelectionException;
import com.comhu.bidmonitor.submission.service.SubmissionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        SubmissionCaseController.class,
        SubmissionProjectController.class,
        CommonSubmissionDocumentController.class,
        SubmissionDocumentMasterController.class,
        SubmissionPersonnelController.class
})
public class SubmissionApiExceptionHandler {

    @ExceptionHandler(com.comhu.bidmonitor.performance.FmsDriveException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    SubmissionErrorResponse personnelIndex(com.comhu.bidmonitor.performance.FmsDriveException exception) {
        return new SubmissionErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(SubmissionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    SubmissionErrorResponse notFound(SubmissionNotFoundException exception) {
        return new SubmissionErrorResponse(exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidSubmissionSelectionException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    SubmissionErrorResponse badRequest(RuntimeException exception) {
        return new SubmissionErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(CompanyDatabaseUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    SubmissionErrorResponse unavailable(CompanyDatabaseUnavailableException exception) {
        return new SubmissionErrorResponse(exception.getMessage());
    }
}
