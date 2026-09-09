package com.comhu.bidmonitor.submission.persistence;

import com.comhu.bidmonitor.submission.domain.SubmissionCase;

import java.util.Optional;

public interface SubmissionCaseRepository {
    java.util.List<SubmissionCase> findAll();
    SubmissionCase lock(Long id);
    void delete(Long id);
    void update(SubmissionCase value);
    boolean performanceProjectExists(String id);
    long performanceMissing(String id);
    long performanceTotal(String id);
    SubmissionCase save(SubmissionCase submissionCase);

    Optional<SubmissionCase> findById(Long id);

    Optional<SubmissionCase> findByProjectId(Long projectId);
}
