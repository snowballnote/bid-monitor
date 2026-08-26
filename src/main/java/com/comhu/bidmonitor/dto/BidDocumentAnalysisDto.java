package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 첨부문서 원문에서 입찰 검토에 필요한 항목을 규칙 기반으로 추출한 결과를 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BidDocumentAnalysisDto {

    // 제출해야 하는 서류 또는 목록의 원문 조각
    private List<String> requiredDocuments = new ArrayList<>();

    // 입찰 참가자격과 관련된 원문 문장 또는 목록
    private List<String> qualificationRequirements = new ArrayList<>();

    // 방문·우편·온라인 등 제출방법과 제출처 관련 원문 문장
    private List<String> submissionMethods = new ArrayList<>();

    // 접수 또는 제출 마감일시와 관련된 원문 문장
    private List<String> submissionDeadlines = new ArrayList<>();

    // 공동수급·공동도급·공동수급협정서 관련 원문 문장 또는 목록
    private List<String> jointContractRequirements = new ArrayList<>();

    // 문서 핵심정보 분석 상태(NOT_ANALYZED, ANALYZED, FAILED)
    private String analysisStatus = "NOT_ANALYZED";

    // 추출 건수, 미탐지 또는 실패 사유
    private String analysisNote = "";
}
