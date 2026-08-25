package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 나라장터 입찰공고에 등록된 첨부문서 정보를 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BidAttachmentDto {

    // 나라장터에 등록된 첨부파일명
    private String fileName;

    // 첨부파일을 내려받을 수 있는 URL
    private String fileUrl;

    // 파일명으로 우선 분류한 문서 종류(공고문, 과업지시서, 제안요청서, 기타)
    private String documentType;

    // 향후 문서 내용 분석 진행 상태
    private String analysisStatus = "NOT_ANALYZED";
}
