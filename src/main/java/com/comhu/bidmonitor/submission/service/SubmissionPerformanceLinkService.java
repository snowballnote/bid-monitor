package com.comhu.bidmonitor.submission.service;
import com.comhu.bidmonitor.submission.domain.SubmissionCase;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import com.comhu.bidmonitor.performance.PerformanceRepository;
import com.comhu.bidmonitor.performance.PerformanceModels.ProjectInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
@Service
public class SubmissionPerformanceLinkService {
    private final SubmissionCaseRepository cases;
    private final PerformanceRepository performances;
    private final Clock clock;
    public SubmissionPerformanceLinkService(SubmissionCaseRepository cases, PerformanceRepository performances, Clock clock) {
        this.cases=cases; this.performances=performances; this.clock=clock;
    }
    @Transactional
    public SubmissionCase ensure(Long caseId) {
        // Same H2 transaction and row lock serialize concurrent clicks and tabs.
        var current=cases.lock(caseId);
        if (current.getPerformanceProjectId()!=null) {
            if (!cases.performanceProjectExists(current.getPerformanceProjectId()))
                throw new SubmissionNotFoundException("연결된 실적 프로젝트를 찾을 수 없습니다.");
            return current;
        }
        if (current.getDeadline()==null) throw new IllegalArgumentException("프로젝트 마감일을 먼저 저장하세요.");
        var project=performances.create(new ProjectInput(current.getProjectName(),current.getDeadline()));
        var linked=current.toBuilder().performanceProjectId(project.id()).performanceLinkInitialized(true).updatedAt(clock.instant()).build();
        cases.update(linked);
        return linked;
    }
}
