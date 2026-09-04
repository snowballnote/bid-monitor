package com.comhu.bidmonitor.externalnotice.api;

import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeCollectionResponse;
import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeDetailResponse;
import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeListResponse;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionWorkflow;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 외부공지 수집을 수동 실행하고 저장된 현재값을 확인하기 위한 간단한 REST API이다. */
@RestController
@RequestMapping("/api/external-notices")
public class ExternalNoticeController {

    private final ExternalNoticeCollectionWorkflow collectionWorkflow;
    private final ExternalNoticeRepository repository;

    public ExternalNoticeController(
            ExternalNoticeCollectionWorkflow collectionWorkflow,
            ExternalNoticeRepository repository
    ) {
        this.collectionWorkflow = collectionWorkflow;
        this.repository = repository;
    }

    @PostMapping("/collect")
    public ExternalNoticeCollectionResponse collect() {
        return ExternalNoticeCollectionResponse.from(collectionWorkflow.run().collectionResult());
    }

    @GetMapping
    public List<ExternalNoticeListResponse> getNotices(
            @RequestParam(required = false) Boolean piaRelated
    ) {
        List<ExternalNotice> notices = piaRelated == null
                ? repository.findAllLatestFirst()
                : repository.findAllByPiaRelatedLatestFirst(piaRelated);
        return notices.stream().map(ExternalNoticeListResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ExternalNoticeDetailResponse getNotice(@PathVariable Long id) {
        ExternalNotice notice = repository.findById(id)
                .orElseThrow(() -> new ExternalNoticeNotFoundException(id));
        return ExternalNoticeDetailResponse.from(notice);
    }
}
