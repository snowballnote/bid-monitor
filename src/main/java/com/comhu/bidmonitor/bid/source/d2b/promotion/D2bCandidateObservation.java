package com.comhu.bidmonitor.bid.source.d2b.promotion;

import java.util.Map;
import java.util.Set;

/** Collected public fields only; never an HTTP request, response header or request URL. */
public record D2bCandidateObservation(
        String sourceNoticeId, String revision, Set<String> operations, Set<String> paths,
        String title, Map<String, String> listFields, Map<String, String> detailFields,
        String detailStatus
) {
    public D2bCandidateObservation {
        operations = Set.copyOf(operations);
        paths = Set.copyOf(paths);
        listFields = Map.copyOf(listFields);
        detailFields = Map.copyOf(detailFields);
    }
}
