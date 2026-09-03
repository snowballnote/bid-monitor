package com.comhu.bidmonitor.submission.persistence;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;

import java.util.List;
import java.util.Optional;

public interface SubmissionRequirementRepository {
    List<SubmissionDocumentRequirement> saveAll(List<SubmissionDocumentRequirement> requirements);

    Optional<SubmissionDocumentRequirement> findById(Long id);

    List<SubmissionDocumentRequirement> findBySubmissionCaseId(Long submissionCaseId);
}
