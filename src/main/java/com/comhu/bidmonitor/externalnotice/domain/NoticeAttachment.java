package com.comhu.bidmonitor.externalnotice.domain;

import lombok.Builder;
import lombok.Value;

/** 외부 공지 상세페이지에 연결된 첨부파일의 표시명과 다운로드 주소이다. */
@Value
@Builder
public class NoticeAttachment {

    String fileName;
    String fileUrl;
}
