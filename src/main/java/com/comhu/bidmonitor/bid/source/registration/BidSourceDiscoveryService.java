package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class BidSourceDiscoveryService {

    private final BidSourceRegistrationRepository registrationRepository;
    private final BidSourceDiscoveryResultRepository discoveryRepository;
    private final BidSourceAvailabilityChecker availabilityChecker;
    private final BidSourceSiteStructureAnalyzer analyzer;
    private final Clock clock;

    public BidSourceDiscoveryService(
            BidSourceRegistrationRepository registrationRepository,
            BidSourceDiscoveryResultRepository discoveryRepository,
            BidSourceAvailabilityChecker availabilityChecker,
            BidSourceSiteStructureAnalyzer analyzer,
            Clock clock
    ) {
        this.registrationRepository = registrationRepository;
        this.discoveryRepository = discoveryRepository;
        this.availabilityChecker = availabilityChecker;
        this.analyzer = analyzer;
        this.clock = clock;
    }

    public BidSourceDiscoveryResult find(long sourceId) {
        BidSourceRegistration registration = registration(sourceId);
        return discoveryRepository.findBySourceId(sourceId).orElseGet(() -> notAnalyzed(registration));
    }

    public BidSourceDiscoveryResult analyze(long sourceId) {
        BidSourceRegistration registration = registration(sourceId);
        Instant startedAt = clock.instant();
        discoveryRepository.save(base(registration, BidSourceDiscoveryResult.DiscoveryStatus.ANALYZING,
                registration.getDetectedCollectionMethod(), startedAt, List.of("ANALYSIS_STARTED")));

        if (registration.getCheckStatus() != BidSourceRegistration.CheckStatus.REACHABLE) {
            return discoveryRepository.save(base(registration, BidSourceDiscoveryResult.DiscoveryStatus.FAILED,
                    registration.getDetectedCollectionMethod(), clock.instant(),
                    List.of("AVAILABILITY_CHECK_REQUIRED")));
        }

        try {
            BidSourceAvailabilityChecker.FetchResult fetched = availabilityChecker.fetch(registration.getSiteUrl());
            if (fetched.failureCode() != null) {
                return discoveryRepository.save(base(registration, BidSourceDiscoveryResult.DiscoveryStatus.FAILED,
                        BidSourceRegistration.CollectionMethod.UNDETERMINED, clock.instant(),
                        List.of("FETCH_" + fetched.failureCode().name())));
            }

            BidSourceSiteStructureAnalyzer.Analysis analysis = analyzer.analyze(
                    fetched.uri(), fetched.contentType(), fetched.body()
            );
            if (analysis.detailCandidate() != null) {
                BidSourceAvailabilityChecker.FetchResult detail = availabilityChecker.fetch(
                        analysis.detailCandidate().toString()
                );
                analysis = detail.failureCode() == null
                        ? analyzer.withDetail(analysis, detail.contentType(), detail.body())
                        : analysis.withDetailFailure("DETAIL_FETCH_" + detail.failureCode().name());
            }
            return discoveryRepository.save(toResult(sourceId, analysis, clock.instant()));
        } catch (RuntimeException exception) {
            return discoveryRepository.save(base(registration, BidSourceDiscoveryResult.DiscoveryStatus.FAILED,
                    BidSourceRegistration.CollectionMethod.UNDETERMINED, clock.instant(),
                    List.of("ANALYSIS_FAILED")));
        }
    }

    private BidSourceDiscoveryResult toResult(
            long sourceId,
            BidSourceSiteStructureAnalyzer.Analysis analysis,
            Instant analyzedAt
    ) {
        return BidSourceDiscoveryResult.builder()
                .sourceId(sourceId)
                .discoveryStatus(analysis.status())
                .detectedCollectionMethod(analysis.method())
                .listPageUrl(analysis.listPageUrl())
                .detailUrlPattern(analysis.detailUrlPattern())
                .identifierConfidence(analysis.identifierConfidence())
                .titleConfidence(analysis.titleConfidence())
                .deadlineConfidence(analysis.deadlineConfidence())
                .attachmentDetected(analysis.attachmentDetected())
                .paginationDetected(analysis.paginationDetected())
                .reasonCodes(new ArrayList<>(analysis.reasonCodes()))
                .analyzedAt(analyzedAt)
                .build();
    }

    private BidSourceDiscoveryResult notAnalyzed(BidSourceRegistration registration) {
        return base(registration, BidSourceDiscoveryResult.DiscoveryStatus.NOT_ANALYZED,
                BidSourceRegistration.CollectionMethod.UNDETERMINED, null, List.of());
    }

    private BidSourceDiscoveryResult base(
            BidSourceRegistration registration,
            BidSourceDiscoveryResult.DiscoveryStatus status,
            BidSourceRegistration.CollectionMethod method,
            Instant analyzedAt,
            List<String> reasons
    ) {
        return BidSourceDiscoveryResult.builder()
                .sourceId(registration.getSourceId())
                .discoveryStatus(status)
                .detectedCollectionMethod(method == null
                        ? BidSourceRegistration.CollectionMethod.UNDETERMINED : method)
                .listPageUrl(registration.getSiteUrl())
                .identifierConfidence(BidSourceDiscoveryResult.Confidence.NONE)
                .titleConfidence(BidSourceDiscoveryResult.Confidence.NONE)
                .deadlineConfidence(BidSourceDiscoveryResult.Confidence.NONE)
                .reasonCodes(reasons)
                .analyzedAt(analyzedAt)
                .build();
    }

    private BidSourceRegistration registration(long sourceId) {
        return registrationRepository.findById(sourceId)
                .orElseThrow(BidSourceRegistrationNotFoundException::new);
    }
}
