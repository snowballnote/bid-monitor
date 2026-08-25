package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

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

    // 첨부문서에서 외부 기관 또는 사이트를 확인해야 할 가능성이 탐지되었는지 여부
    private Boolean externalReferenceDetected = false;

    // 첨부문서 본문에서 탐지한 나라장터 외부 URL 목록
    private List<String> detectedExternalUrls = new ArrayList<>();

    // 탐지된 키워드, URL 또는 분석 실패 사유
    private String analysisReason = "";

    /**
     * 기존 첨부파일 생성 코드와 호환하면서 분석 결과는 기본값으로 초기화한다.
     */
    public BidAttachmentDto(String fileName, String fileUrl, String documentType, String analysisStatus) {
        this.fileName = fileName;
        this.fileUrl = fileUrl;
        this.documentType = documentType;
        this.analysisStatus = analysisStatus;
    }
}
