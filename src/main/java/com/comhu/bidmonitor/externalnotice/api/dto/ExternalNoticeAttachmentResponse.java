package com.comhu.bidmonitor.externalnotice.api.dto;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import lombok.Builder;
import lombok.Value;

/** 상세 API에 노출하는 첨부파일 정보이며 내부 DB 식별자는 숨긴다. */
@Value
@Builder
public class ExternalNoticeAttachmentResponse {

    String fileName;
    String fileUrl;

    public static ExternalNoticeAttachmentResponse from(ExternalNoticeAttachment attachment) {
        return ExternalNoticeAttachmentResponse.builder()
                .fileName(attachment.getFileName())
                .fileUrl(attachment.getFileUrl())
                .build();
    }
}
