package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** 아직 파일을 복사하지 않고 선택된 metadata만 묶어 보여주는 MVP 제출 패키지다. */
@Getter
@Builder
public class SubmissionPackage {
    private final SubmissionCase submissionCase;
    @Builder.Default
    private final List<SubmissionDocumentRequirement> requirements = List.of();
    @Builder.Default
    private final List<SubmissionDocumentSelection> selections = List.of();
}
