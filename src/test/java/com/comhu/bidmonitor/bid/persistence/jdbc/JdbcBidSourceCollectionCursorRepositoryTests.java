package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursor;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-source-cursor;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class JdbcBidSourceCollectionCursorRepositoryTests {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);
    private static final Instant CREATED = Instant.parse("2026-10-15T00:00:00Z");

    @Autowired
    private JdbcBidSourceCollectionCursorRepository repository;

    @Autowired
    private JdbcBidSourceCollectionRetryRepository retryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM bid_source_collection_retry");
        jdbcTemplate.update("DELETE FROM bid_source_collection_cursor");
    }

    @Test
    void createsOnceAndAdvancesWithOptimisticVersion() {
        BidSourceCollectionCursor created = repository.getOrCreate(
                "D2B", "FULL_DATE", "DOMESTIC_COMPETITIVE", DATE, DATE, 10, CREATED
        );
        BidSourceCollectionCursor same = repository.getOrCreate(
                "D2B", "FULL_DATE", "DOMESTIC_COMPETITIVE", DATE, DATE, 10, CREATED.plusSeconds(1)
        );

        assertEquals(created.id(), same.id());
        assertEquals(1, repository.findIncomplete("D2B", "FULL_DATE").size());
        assertTrue(repository.advance(
                created.id(), created.version(), 2, 15,
                BidSourceCollectionCursor.Status.PARTIAL, CREATED.plusSeconds(2)
        ));
        assertFalse(repository.advance(
                created.id(), created.version(), 3, 15,
                BidSourceCollectionCursor.Status.COMPLETE, CREATED.plusSeconds(3)
        ));

        BidSourceCollectionCursor partial = repository.findIncomplete("D2B", "FULL_DATE").getFirst();
        assertEquals(2, partial.nextPage());
        assertEquals(15, partial.totalCountSnapshot());
        assertEquals(1, partial.version());
        assertTrue(repository.advance(
                partial.id(), partial.version(), 3, 15,
                BidSourceCollectionCursor.Status.COMPLETE, CREATED.plusSeconds(4)
        ));
        assertTrue(repository.findIncomplete("D2B", "FULL_DATE").isEmpty());

        assertTrue(repository.reopenCompleted(
                partial.id(), partial.version() + 1, CREATED.plusSeconds(5)
        ));
        BidSourceCollectionCursor reopened = repository.findIncomplete("D2B", "FULL_DATE").getFirst();
        assertEquals(1, reopened.nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, reopened.status());
    }

    @Test
    void savesUpdatesRecordsAndDeletesDurableRetry() {
        BidSourceCollectionRetry created = retryRepository.savePending(
                "D2B", "FULL_DATE", "DOMESTIC_COMPETITIVE", DATE, DATE, 2,
                "2026:ORG:NOTICE-1", "001", "{\"bidNm\":\"fixture\"}", CREATED
        );
        BidSourceCollectionRetry same = retryRepository.savePending(
                "D2B", "FULL_DATE", "DOMESTIC_COMPETITIVE", DATE, DATE, 3,
                "2026:ORG:NOTICE-1", "001", "{\"bidNm\":\"updated\"}",
                CREATED.plusSeconds(1)
        );

        assertEquals(created.id(), same.id());
        assertEquals(3, same.pageNumber());
        assertEquals("{\"bidNm\":\"updated\"}", same.payload());

        retryRepository.recordFailure(created.id(), "HTTP", CREATED.plusSeconds(2));
        BidSourceCollectionRetry failed = retryRepository.findPending("D2B", "FULL_DATE").getFirst();
        assertEquals(1, failed.retryCount());
        assertEquals("HTTP", failed.lastErrorCode());
        assertEquals(CREATED.plusSeconds(2), failed.lastAttemptAt());

        retryRepository.delete(created.id());
        assertTrue(retryRepository.findPending("D2B", "FULL_DATE").isEmpty());
    }
}
