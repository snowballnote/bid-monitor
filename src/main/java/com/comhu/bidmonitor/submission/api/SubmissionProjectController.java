package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.api.dto.SubmissionProjectResponse;
import com.comhu.bidmonitor.submission.service.SubmissionProjectSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 제출서류 작업을 시작할 PMS 사업을 회사 DB에서 읽기 전용으로 검색한다. */
@RestController
@RequestMapping("/api/submission-projects")
public class SubmissionProjectController {

    private final SubmissionProjectSearchService service;

    public SubmissionProjectController(SubmissionProjectSearchService service) {
        this.service = service;
    }

    @GetMapping
    public List<SubmissionProjectResponse> search(@RequestParam String query) {
        return service.search(query).stream().map(SubmissionProjectResponse::from).toList();
    }
}
