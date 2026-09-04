package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.api.dto.CommonSubmissionDocumentResponse;
import com.comhu.bidmonitor.submission.api.dto.UpdateCommonSubmissionDocumentRequest;
import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.service.CommonSubmissionDocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 공통 제출서류의 현재 사용본과 갱신 날짜를 관리하는 내부 API다. */
@RestController
@RequestMapping("/api/submission-common-documents")
public class CommonSubmissionDocumentController {

    private final CommonSubmissionDocumentService service;

    public CommonSubmissionDocumentController(CommonSubmissionDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<CommonSubmissionDocumentResponse> findAll() {
        return service.findAll().stream().map(CommonSubmissionDocumentResponse::from).toList();
    }

    @PutMapping("/{documentType}")
    public CommonSubmissionDocumentResponse update(
            @PathVariable CommonDocumentType documentType,
            @RequestBody UpdateCommonSubmissionDocumentRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("요청 본문이 필요합니다.");
        }
        return CommonSubmissionDocumentResponse.from(service.updateCurrentReference(
                documentType,
                request.fileId(),
                request.issuedAt(),
                request.expiresAt()
        ));
    }
}
