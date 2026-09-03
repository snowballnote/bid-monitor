package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionProjectSearchServiceTests {

    private final PmsProjectQueryPort projectQueryPort = mock(PmsProjectQueryPort.class);
    private final SubmissionProjectSearchService service = new SubmissionProjectSearchService(projectQueryPort);

    @Test
    void trimsQueryAndLimitsCompanyDatabaseResultsToTwenty() {
        var expected = List.of(new PmsProjectQueryPort.PmsProjectSummary(
                301L, "공공정보시스템 고도화", "테스트 발주기관", "20260903-01"
        ));
        when(projectQueryPort.searchProjects("고도화", 20)).thenReturn(expected);

        assertThat(service.search("  고도화  ")).isEqualTo(expected);
        verify(projectQueryPort).searchProjects("고도화", 20);
    }

    @Test
    void rejectsBlankOrOverlongQueriesBeforeCompanyDatabaseAccess() {
        assertThatThrownBy(() -> service.search("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검색어");
        assertThatThrownBy(() -> service.search("가".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100자");
    }
}
