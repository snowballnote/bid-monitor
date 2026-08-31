package com.comhu.bidmonitor.externalnotice.collector;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeSource;

import java.util.List;

/** 기관별 수집 구현을 공통 수집 흐름에서 동일한 방식으로 호출하기 위한 계약이다. */
public interface ExternalNoticeCollector {

    NoticeSource getSource();

    List<CollectedNotice> collect();
}
