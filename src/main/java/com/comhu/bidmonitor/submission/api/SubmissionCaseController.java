package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.api.dto.CreateSubmissionCaseRequest;
import com.comhu.bidmonitor.submission.api.dto.DocumentCandidateResponse;
import com.comhu.bidmonitor.submission.api.dto.ReplaceSubmissionSelectionsRequest;
import com.comhu.bidmonitor.submission.api.dto.SubmissionCaseResponse;
import com.comhu.bidmonitor.submission.api.dto.SubmissionPackageResponse;
import com.comhu.bidmonitor.submission.api.dto.SubmissionRequirementResponse;
import com.comhu.bidmonitor.submission.api.dto.SubmissionSelectionResponse;
import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.service.SubmissionCaseService;
import com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement;
import com.comhu.bidmonitor.submission.service.SubmissionCaseService.SelectionChoice;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 제출서류 MVP의 작업 생성·요구서류·후보·선택·패키지 조회 API다. */
@RestController
@RequestMapping("/api/submission-cases")
public class SubmissionCaseController {

    private final SubmissionCaseService service;

    public SubmissionCaseController(SubmissionCaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionCaseResponse create(@RequestBody CreateSubmissionCaseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("요청 본문이 필요합니다.");
        }
        if (request.requirements() != null && !request.requirements().isEmpty()) {
            List<ManualRequirement> requirements = request.requirements().stream()
                    .map(item -> new ManualRequirement(
                            parseCategory(item.category()), item.documentName(), item.sourceReference()
                    ))
                    .toList();
            return SubmissionCaseResponse.from(service.createManual(requirements));
        }
        return SubmissionCaseResponse.from(service.create(request.projectId()));
    }

    @GetMapping("/{id}")
    public SubmissionCaseResponse get(@PathVariable Long id) {
        return SubmissionCaseResponse.from(service.get(id));
    }

    @GetMapping("/{id}/requirements")
    public List<SubmissionRequirementResponse> requirements(@PathVariable Long id) {
        return service.getRequirements(id).stream().map(SubmissionRequirementResponse::from).toList();
    }

    @GetMapping("/{id}/requirements/{requirementId}/candidates")
    public List<DocumentCandidateResponse> candidates(
            @PathVariable Long id,
            @PathVariable Long requirementId
    ) {
        return service.findCandidates(id, requirementId).stream().map(DocumentCandidateResponse::from).toList();
    }

    @PutMapping("/{id}/selections")
    public List<SubmissionSelectionResponse> replaceSelections(
            @PathVariable Long id,
            @RequestBody ReplaceSubmissionSelectionsRequest request
    ) {
        List<SelectionChoice> choices = request == null || request.selections() == null
                ? List.of()
                : request.selections().stream()
                .map(item -> new SelectionChoice(item.requirementId(), item.fileId()))
                .toList();
        return service.replaceSelections(id, choices).stream().map(SubmissionSelectionResponse::from).toList();
    }

    @GetMapping("/{id}/package")
    public SubmissionPackageResponse getPackage(@PathVariable Long id) {
        return SubmissionPackageResponse.from(service.getPackage(id));
    }

    private RequirementCategory parseCategory(String value) {
        try {
            return RequirementCategory.valueOf(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 제출서류 카테고리입니다.");
        }
    }
}
