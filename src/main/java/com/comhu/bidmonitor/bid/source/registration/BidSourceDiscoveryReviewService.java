package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewAudit;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewAuditRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

@Service
public class BidSourceDiscoveryReviewService {

    private static final int MAX_URL_LENGTH = 4000;
    private static final int MAX_MAPPING_LENGTH = 2000;

    private final BidSourceRegistrationRepository registrationRepository;
    private final BidSourceDiscoveryResultRepository discoveryRepository;
    private final BidSourceDiscoveryReviewRepository reviewRepository;
    private final BidSourceDiscoveryReviewAuditRepository auditRepository;
    private final Clock clock;

    public BidSourceDiscoveryReviewService(
            BidSourceRegistrationRepository registrationRepository,
            BidSourceDiscoveryResultRepository discoveryRepository,
            BidSourceDiscoveryReviewRepository reviewRepository,
            BidSourceDiscoveryReviewAuditRepository auditRepository,
            Clock clock
    ) {
        this.registrationRepository = registrationRepository;
        this.discoveryRepository = discoveryRepository;
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional
    public BidSourceDiscoveryReview find(long sourceId) {
        requireRegistration(sourceId);
        return reviewRepository.findBySourceId(sourceId)
                .orElseGet(() -> initialize(discoveryRepository.findBySourceId(sourceId).orElse(null), sourceId));
    }

    @Transactional
    public BidSourceDiscoveryReview initialize(BidSourceDiscoveryResult discovery) {
        if (discovery == null) {
            throw new IllegalArgumentException("Discovery result is required.");
        }
        requireRegistration(discovery.getSourceId());
        return reviewRepository.findBySourceId(discovery.getSourceId())
                .orElseGet(() -> initialize(discovery, discovery.getSourceId()));
    }

    @Transactional
    public BidSourceDiscoveryReview review(long sourceId, Update update, String actor) {
        if (update == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Audit actor is required.");
        }
        BidSourceDiscoveryReview current = find(sourceId);
        BidSourceDiscoveryReview.ReviewStatus targetStatus = parseStatus(update.reviewStatus());
        Instant changedAt = clock.instant();
        BidSourceDiscoveryReview updated = BidSourceDiscoveryReview.builder()
                .sourceId(sourceId)
                .reviewStatus(targetStatus)
                .listPageUrl(value(update.listPageUrl(), current.getListPageUrl(), "listPageUrl", MAX_URL_LENGTH))
                .detailUrlPattern(value(update.detailUrlPattern(), current.getDetailUrlPattern(),
                        "detailUrlPattern", MAX_URL_LENGTH))
                .identifierMapping(value(update.identifierMapping(), current.getIdentifierMapping(),
                        "identifierMapping", MAX_MAPPING_LENGTH))
                .titleMapping(value(update.titleMapping(), current.getTitleMapping(),
                        "titleMapping", MAX_MAPPING_LENGTH))
                .agencyMapping(value(update.agencyMapping(), current.getAgencyMapping(),
                        "agencyMapping", MAX_MAPPING_LENGTH))
                .publishedDateMapping(value(update.publishedDateMapping(), current.getPublishedDateMapping(),
                        "publishedDateMapping", MAX_MAPPING_LENGTH))
                .deadlineMapping(value(update.deadlineMapping(), current.getDeadlineMapping(),
                        "deadlineMapping", MAX_MAPPING_LENGTH))
                .statusMapping(value(update.statusMapping(), current.getStatusMapping(),
                        "statusMapping", MAX_MAPPING_LENGTH))
                .attachmentMapping(value(update.attachmentMapping(), current.getAttachmentMapping(),
                        "attachmentMapping", MAX_MAPPING_LENGTH))
                .paginationMapping(value(update.paginationMapping(), current.getPaginationMapping(),
                        "paginationMapping", MAX_MAPPING_LENGTH))
                .updatedAt(changedAt)
                .build();

        validateUrl(updated.getListPageUrl());
        validateDetailPattern(updated.getDetailUrlPattern());
        if (targetStatus == BidSourceDiscoveryReview.ReviewStatus.APPROVED) {
            validateApproval(sourceId, updated);
        }
        BidSourceDiscoveryReview saved = reviewRepository.save(updated);
        auditRepository.save(new BidSourceDiscoveryReviewAudit(
                null, sourceId, action(targetStatus), current.getReviewStatus(), targetStatus,
                actor.trim(), changedAt
        ));
        return saved;
    }

    private BidSourceDiscoveryReview initialize(BidSourceDiscoveryResult discovery, long sourceId) {
        return reviewRepository.save(BidSourceDiscoveryReview.builder()
                .sourceId(sourceId)
                .reviewStatus(BidSourceDiscoveryReview.ReviewStatus.PENDING_REVIEW)
                .listPageUrl(known(discovery == null ? null : discovery.getListPageUrl()))
                .detailUrlPattern(known(discovery == null ? null : discovery.getDetailUrlPattern()))
                .identifierMapping(known(discovery == null ? null : discovery.getIdentifierMapping()))
                .titleMapping(known(discovery == null ? null : discovery.getTitleMapping()))
                .agencyMapping(known(discovery == null ? null : discovery.getAgencyMapping()))
                .publishedDateMapping(known(discovery == null ? null : discovery.getPublishedDateMapping()))
                .deadlineMapping(known(discovery == null ? null : discovery.getDeadlineMapping()))
                .statusMapping(known(discovery == null ? null : discovery.getStatusMapping()))
                .attachmentMapping(known(discovery == null ? null : discovery.getAttachmentMapping()))
                .paginationMapping(known(discovery == null ? null : discovery.getPaginationMapping()))
                .updatedAt(clock.instant())
                .build());
    }

    private void validateApproval(long sourceId, BidSourceDiscoveryReview review) {
        BidSourceDiscoveryResult discovery = discoveryRepository.findBySourceId(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Discovery must be READY before approval."));
        if (discovery.getDiscoveryStatus() != BidSourceDiscoveryResult.DiscoveryStatus.READY) {
            throw new IllegalArgumentException("Discovery must be READY before approval.");
        }
        required(review.getListPageUrl(), "listPageUrl");
        required(review.getDetailUrlPattern(), "detailUrlPattern");
        required(review.getIdentifierMapping(), "identifierMapping");
        required(review.getTitleMapping(), "titleMapping");
    }

    private void requireRegistration(long sourceId) {
        registrationRepository.findById(sourceId)
                .orElseThrow(BidSourceRegistrationNotFoundException::new);
    }

    private BidSourceDiscoveryReview.ReviewStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reviewStatus is required.");
        }
        try {
            return BidSourceDiscoveryReview.ReviewStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("reviewStatus is invalid.");
        }
    }

    private String value(String requested, String current, String field, int maxLength) {
        if (requested == null) return current;
        String trimmed = requested.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " cannot be blank.");
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(field + " is too long.");
        return trimmed;
    }

    private void validateUrl(String value) {
        if (BidSourceDiscoveryReview.UNKNOWN.equals(value)) return;
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("listPageUrl must be an HTTP(S) URL without credentials.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("listPageUrl must be an HTTP(S) URL without credentials.");
        }
    }

    private void validateDetailPattern(String value) {
        if (BidSourceDiscoveryReview.UNKNOWN.equals(value)) return;
        String sample = value.replace("{key}", "1").replace("{value}", "1");
        try {
            URI uri = URI.create(sample);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "detailUrlPattern must be an HTTP(S) URL pattern without credentials."
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "detailUrlPattern must be an HTTP(S) URL pattern without credentials."
            );
        }
    }

    private void required(String value, String field) {
        if (value == null || value.isBlank() || BidSourceDiscoveryReview.UNKNOWN.equals(value)) {
            throw new IllegalArgumentException(field + " is required for approval.");
        }
    }

    private String known(String value) {
        return value == null || value.isBlank() ? BidSourceDiscoveryReview.UNKNOWN : value;
    }

    private BidSourceDiscoveryReviewAudit.Action action(BidSourceDiscoveryReview.ReviewStatus status) {
        return switch (status) {
            case APPROVED -> BidSourceDiscoveryReviewAudit.Action.APPROVED;
            case REJECTED -> BidSourceDiscoveryReviewAudit.Action.REJECTED;
            case PENDING_REVIEW -> BidSourceDiscoveryReviewAudit.Action.REVIEW_UPDATED;
        };
    }

    public record Update(
            String reviewStatus,
            String listPageUrl,
            String detailUrlPattern,
            String identifierMapping,
            String titleMapping,
            String agencyMapping,
            String publishedDateMapping,
            String deadlineMapping,
            String statusMapping,
            String attachmentMapping,
            String paginationMapping
    ) { }
}
