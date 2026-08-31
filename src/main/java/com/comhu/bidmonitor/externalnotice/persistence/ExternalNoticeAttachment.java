package com.comhu.bidmonitor.externalnotice.persistence;

import lombok.Builder;
import lombok.Value;

/** DB에 저장된 외부공지 첨부파일 한 건이다. */
@Value
@Builder
public class ExternalNoticeAttachment {

    Long id;
    Long externalNoticeId;
    String fileName;
    String fileUrl;
}
