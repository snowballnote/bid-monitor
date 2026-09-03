package com.comhu.bidmonitor.submission.api.dto;

import java.util.List;

public record ReplaceSubmissionSelectionsRequest(List<SelectionItem> selections) {
    public record SelectionItem(Long requirementId, Long fileId) {
    }
}
