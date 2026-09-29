package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BidSourceExecutionEligibilityServiceTests {

    private final BidSourceRegistrationRepository repository = mock(BidSourceRegistrationRepository.class);
    private final BidSourceExecutionEligibilityService service =
            new BidSourceExecutionEligibilityService(repository);

    @Test
    void rejectsApprovedBoundRegistrationWhileRegistrationExecutionIsDisabled() {
        BidCandidateCollector collector = collector("KOGAS", true);
        when(repository.findBySourceCode("KOGAS")).thenReturn(Optional.of(registration(
                "KOGAS", true, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, false
        )));

        assertFalse(service.isEligible(collector));
    }

    @Test
    void rejectsMissingBindingMethodMismatchAndMissingCollector() {
        BidCandidateCollector collector = collector("KOGAS", true);
        assertFalse(service.isEligible(registration(
                null, true, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, true
        ), collector));
        assertFalse(service.isEligible(registration(
                "KOGAS", true, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.RSS, true
        ), collector));
        assertTrue(service.findEligibleCollector("KOGAS", List.of()).isEmpty());
    }

    @Test
    void requiresApprovedRegistrationAndCollectorStaticPermission() {
        assertFalse(service.isEligible(registration(
                "KOGAS", false, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, true
        ), collector("KOGAS", true)));
        assertFalse(service.isEligible(registration(
                "KOGAS", true, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, true
        ), collector("KOGAS", false)));
        assertTrue(service.isEligible(registration(
                "KOGAS", true, BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, true
        ), collector("KOGAS", true)));
    }

    private BidSourceRegistration registration(
            String sourceCode,
            boolean approved,
            BidSourceRegistration.CollectionMethod collectionMethod,
            BidSourceRegistration.CollectionMethod detectedMethod,
            boolean executionEnabled
    ) {
        return BidSourceRegistration.builder()
                .sourceCode(sourceCode)
                .registrationStatus(approved
                        ? BidSourceRegistration.RegistrationStatus.APPROVED
                        : BidSourceRegistration.RegistrationStatus.UNDER_REVIEW)
                .collectionMethod(collectionMethod)
                .detectedCollectionMethod(detectedMethod)
                .executionEnabled(executionEnabled)
                .build();
    }

    private BidCandidateCollector collector(String sourceCode, boolean executionEnabled) {
        return new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public boolean executionEnabled() {
                return executionEnabled;
            }

            @Override
            public boolean registrationBindingSupported() {
                return true;
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                throw new AssertionError("Eligibility checks must not collect bids.");
            }
        };
    }
}
