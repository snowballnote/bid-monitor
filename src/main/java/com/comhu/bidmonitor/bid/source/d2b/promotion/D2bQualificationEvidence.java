package com.comhu.bidmonitor.bid.source.d2b.promotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable evidence, independent of collection, retry and persistence lifecycles. */
public record D2bQualificationEvidence(
        DetailStatus detailStatus,
        String lcnsLmttList,
        String industClList,
        String originalConditions,
        ParseCompleteness completeness,
        List<List<String>> licenseGroups
) {
    public enum DetailStatus { AVAILABLE, FAILED, ITEM_MISSING }

    public enum ParseCompleteness {
        UNVERIFIED,
        PARTIAL,
        /**
         * An evidence producer has verified every OR alternative and every AND requirement,
         * reconciled both source fields and original conditions, and left no unknown clauses.
         * Extracting codes or flattening them into one group is NOT sufficient certification.
         */
        VERIFIED_COMPLETE
    }

    public D2bQualificationEvidence {
        Objects.requireNonNull(detailStatus, "detailStatus");
        Objects.requireNonNull(completeness, "completeness");
        List<List<String>> snapshot = new ArrayList<>();
        if (licenseGroups != null) {
            for (List<String> group : licenseGroups) {
                snapshot.add(group == null ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(group)));
            }
        }
        licenseGroups = List.copyOf(snapshot);
    }

    /** null means unavailable evidence; blank means a field inspected and found empty. */
    public static D2bQualificationEvidence rawDetail(
            String lcnsLmttList, String industClList, String originalConditions
    ) {
        return new D2bQualificationEvidence(DetailStatus.AVAILABLE,
                lcnsLmttList, industClList, originalConditions,
                ParseCompleteness.UNVERIFIED, List.of());
    }
}
