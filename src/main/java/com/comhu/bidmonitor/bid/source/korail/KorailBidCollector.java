package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects KORAIL bid metadata without registering or enabling the source for live collection.
 */
public final class KorailBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "KORAIL";

    private final KorailBidHttpClient httpClient;
    private final KorailBidDtoMapper dtoMapper;

    public KorailBidCollector(KorailBidHttpClient httpClient, KorailBidDtoMapper dtoMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.dtoMapper = Objects.requireNonNull(dtoMapper, "dtoMapper");
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    public boolean executionEnabled() {
        return false;
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);

        List<KorailBidListParser.Notice> notices = httpClient.fetchList(startDate, endDate);
        Map<NoticeKey, KorailBidListParser.Notice> uniqueNotices = new LinkedHashMap<>();
        for (KorailBidListParser.Notice notice : notices) {
            Objects.requireNonNull(notice, "KORAIL list response contains a null notice.");
            uniqueNotices.putIfAbsent(new NoticeKey(notice.noticeNo(), notice.round()), notice);
        }

        List<BidQualificationDto> qualifications = new ArrayList<>(uniqueNotices.size());
        for (KorailBidListParser.Notice notice : uniqueNotices.values()) {
            KorailBidDtoMapper.MappingResult mapped;
            try {
                KorailBidDetailParser.ParsedDetail detail =
                        httpClient.fetchDetail(notice.noticeNo(), notice.round());
                mapped = dtoMapper.map(notice, detail);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                mapped = dtoMapper.map(notice);
            }
            qualifications.add(mapped.qualification());
        }
        return List.copyOf(qualifications);
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("KORAIL collection date range is invalid.");
        }
    }

    private record NoticeKey(String noticeNo, String round) {
        private NoticeKey {
            Objects.requireNonNull(noticeNo, "noticeNo");
            Objects.requireNonNull(round, "round");
        }
    }
}
