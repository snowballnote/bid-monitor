package com.comhu.bidmonitor.submission.persistence;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentSelection;

import java.util.List;

public interface SubmissionSelectionRepository {
    List<SubmissionDocumentSelection> replaceForCase(
            Long submissionCaseId,
            List<SubmissionDocumentSelection> selections
    );

    List<SubmissionDocumentSelection> findBySubmissionCaseId(Long submissionCaseId);
}
