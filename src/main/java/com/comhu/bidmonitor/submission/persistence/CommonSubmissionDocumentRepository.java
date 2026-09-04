package com.comhu.bidmonitor.submission.persistence;

import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.domain.CommonSubmissionDocument;

import java.util.List;
import java.util.Optional;

/** 공통서류 관리정보를 회사 DB가 아닌 Biz Assist H2에 저장하는 계약이다. */
public interface CommonSubmissionDocumentRepository {
    List<CommonSubmissionDocument> findAllActive();

    Optional<CommonSubmissionDocument> findByDocumentType(CommonDocumentType documentType);

    CommonSubmissionDocument updateCurrentReference(CommonSubmissionDocument document);
}
