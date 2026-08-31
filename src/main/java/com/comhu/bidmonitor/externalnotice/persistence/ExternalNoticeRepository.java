package com.comhu.bidmonitor.externalnotice.persistence;

import java.util.List;
import java.util.Optional;

/** 저장 기술과 무관하게 외부공지를 저장하고 조회하기 위한 최소 Repository 계약이다. */
public interface ExternalNoticeRepository {

    ExternalNotice save(ExternalNotice notice);

    Optional<ExternalNotice> findByExternalId(String externalId);

    List<ExternalNotice> findAll();

    boolean existsByExternalId(String externalId);
}
