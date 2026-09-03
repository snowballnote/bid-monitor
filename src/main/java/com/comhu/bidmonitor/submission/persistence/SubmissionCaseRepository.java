package com.comhu.bidmonitor.submission.persistence;

import com.comhu.bidmonitor.submission.domain.SubmissionCase;

import java.util.Optional;

public interface SubmissionCaseRepository {
    SubmissionCase save(SubmissionCase submissionCase);

    Optional<SubmissionCase> findById(Long id);

    Optional<SubmissionCase> findByProjectId(Long projectId);
}
