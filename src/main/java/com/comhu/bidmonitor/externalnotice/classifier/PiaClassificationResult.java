package com.comhu.bidmonitor.externalnotice.classifier;

import lombok.Value;

import java.util.List;

/** PIA 관련 여부와 판단 근거를 이후 저장·표시 단계에 전달하는 단순 이진 판정 결과이다. */
@Value
public class PiaClassificationResult {

    boolean piaRelated;
    List<String> matchedKeywords;
    String reason;

    public PiaClassificationResult(boolean piaRelated, List<String> matchedKeywords, String reason) {
        this.piaRelated = piaRelated;
        this.matchedKeywords = List.copyOf(matchedKeywords);
        this.reason = reason;
    }
}
