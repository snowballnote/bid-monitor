package com.comhu.bidmonitor.bid.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.comhu.bidmonitor.bid.collection.LiveBidCollectionResult;
import com.comhu.bidmonitor.bid.collection.LiveBidCollectionService;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/live-bid-notices")
public class LiveBidNoticeController {

    private final LiveBidCollectionService collectionService;

    public LiveBidNoticeController(LiveBidCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public LiveBidNoticeResponse collect(@RequestBody LiveBidNoticeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        LiveBidCollectionResult result = collectionService.collect(request.startDate(), request.endDate());
        return LiveBidNoticeResponse.from(result);
    }

    public record LiveBidNoticeRequest(
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate endDate
    ) {
    }

    public record LiveBidNoticeResponse(
            String status,
            LocalDate startDate,
            LocalDate endDate,
            List<SourceResponse> sources,
            List<BidQualificationDto> notices
    ) {

        private static LiveBidNoticeResponse from(LiveBidCollectionResult result) {
            return new LiveBidNoticeResponse(
                    result.status().name(),
                    result.startDate(),
                    result.endDate(),
                    result.sources().stream().map(SourceResponse::from).toList(),
                    result.notices()
            );
        }
    }

    public record SourceResponse(
            String sourceCode,
            String status,
            int noticeCount,
            String errorCode
    ) {

        private static SourceResponse from(LiveBidCollectionResult.SourceResult result) {
            return new SourceResponse(
                    result.sourceCode(), result.status().name(),
                    result.notices().size(), result.errorCode()
            );
        }
    }
}
