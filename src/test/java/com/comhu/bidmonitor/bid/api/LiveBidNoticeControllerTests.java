package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.collection.LiveBidCollectionResult;
import com.comhu.bidmonitor.bid.collection.LiveBidCollectionService;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LiveBidNoticeControllerTests {

    private final LiveBidCollectionService service = mock(LiveBidCollectionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LiveBidNoticeController(service))
                .setControllerAdvice(new BidCollectionApiExceptionHandler())
                .build();
    }

    @Test
    void returnsLiveNoticesAndPerSourceStatus() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 22);
        BidQualificationDto notice = new BidQualificationDto();
        notice.setSourceCode("KOGAS");
        notice.setSourceNoticeId("2026-001");
        notice.setBidNtceNo("2026-001");
        notice.setBidNtceNm("정보시스템 감리 용역");
        when(service.collect(date, date)).thenReturn(new LiveBidCollectionResult(
                LiveBidCollectionResult.Status.SUCCESS,
                date,
                date,
                List.of(LiveBidCollectionResult.SourceResult.success("KOGAS", List.of(notice)))
        ));

        mockMvc.perform(post("/api/live-bid-notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-22\",\"endDate\":\"2026-10-22\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.sources[0].sourceCode").value("KOGAS"))
                .andExpect(jsonPath("$.sources[0].noticeCount").value(1))
                .andExpect(jsonPath("$.notices[0].sourceNoticeId").value("2026-001"))
                .andExpect(jsonPath("$.notices[0].bidNtceNm").value("정보시스템 감리 용역"));

        verify(service).collect(date, date);
    }

    @Test
    void rejectsMalformedDatesWithoutRunningCollection() throws Exception {
        mockMvc.perform(post("/api/live-bid-notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"invalid\",\"endDate\":\"2026-10-22\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }
}
