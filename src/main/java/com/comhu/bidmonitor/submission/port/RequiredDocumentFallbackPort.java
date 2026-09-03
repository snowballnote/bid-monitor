package com.comhu.bidmonitor.submission.port;

import java.util.List;

/** PMS RFP에 제출서류 항목이 없을 때 기존 입찰 문서 분석 결과를 재사용하기 위한 port다. */
public interface RequiredDocumentFallbackPort {
    List<String> findRequiredDocuments(String bidNoticeNo);
}
