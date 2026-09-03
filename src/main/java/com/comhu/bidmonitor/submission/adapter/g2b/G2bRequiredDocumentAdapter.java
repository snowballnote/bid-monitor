package com.comhu.bidmonitor.submission.adapter.g2b;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.G2bApiService;
import com.comhu.bidmonitor.submission.port.RequiredDocumentFallbackPort;
import org.springframework.stereotype.Component;

import java.util.List;

/** PMS RFP에 요구서류가 없을 때 기존 G2B 첨부문서 분석 결과만 추출한다. */
@Component
public class G2bRequiredDocumentAdapter implements RequiredDocumentFallbackPort {

    private final G2bApiService g2bApiService;

    public G2bRequiredDocumentAdapter(G2bApiService g2bApiService) {
        this.g2bApiService = g2bApiService;
    }

    @Override
    public List<String> findRequiredDocuments(String bidNoticeNo) {
        if (bidNoticeNo == null || bidNoticeNo.isBlank()) {
            return List.of();
        }
        BidQualificationDto qualification = g2bApiService.getBidQualification(bidNoticeNo.trim());
        if (qualification.getAttachments() == null) {
            return List.of();
        }
        return qualification.getAttachments().stream()
                .filter(attachment -> attachment != null && attachment.getDocumentAnalysis() != null)
                .map(BidAttachmentDto::getDocumentAnalysis)
                .map(BidDocumentAnalysisDto::getRequiredDocuments)
                .filter(documents -> documents != null)
                .flatMap(List::stream)
                .filter(document -> document != null && !document.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
