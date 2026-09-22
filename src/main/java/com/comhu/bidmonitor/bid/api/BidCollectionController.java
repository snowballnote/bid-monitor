package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.api.dto.BidCollectionRequest;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionResponse;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionRunResponse;
import com.comhu.bidmonitor.bid.api.dto.BidSourceStatusResponse;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionResult;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSource;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class BidCollectionController {

    private static final Set<String> DEFAULT_ALLOWED_LICENSE_CODES = Set.of("6146", "1468");

    private final ManualBidCollectionCoordinator coordinator;
    private final BidCollectionRunRepository runRepository;
    private final BidSourceStateRepository stateRepository;
    private final ManualBidCollectionSourceRegistry sourceRegistry;

    public BidCollectionController(
            ManualBidCollectionCoordinator coordinator,
            BidCollectionRunRepository runRepository,
            BidSourceStateRepository stateRepository,
            ManualBidCollectionSourceRegistry sourceRegistry
    ) {
        this.coordinator = coordinator;
        this.runRepository = runRepository;
        this.stateRepository = stateRepository;
        this.sourceRegistry = sourceRegistry;
    }

    @PostMapping("/bid-collections")
    public BidCollectionResponse collect(@RequestBody BidCollectionRequest request) {
        validate(request);
        ManualBidCollectionResult result = coordinator.collect(
                request.startDate(), request.endDate(), DEFAULT_ALLOWED_LICENSE_CODES,
                request.sourceCodes() == null ? Set.of() : request.sourceCodes()
        );
        return BidCollectionResponse.from(result);
    }

    @GetMapping("/bid-collections/{runId}")
    public BidCollectionRunResponse getRun(@PathVariable long runId) {
        BidCollectionRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BidCollectionRunNotFoundException(runId));
        return BidCollectionRunResponse.from(run);
    }

    @GetMapping("/bid-sources/status")
    public List<BidSourceStatusResponse> getSourceStatuses() {
        Map<String, Boolean> enabledBySource = new LinkedHashMap<>();
        for (ManualBidCollectionSource source : sourceRegistry.sources()) {
            enabledBySource.put(source.sourceCode(), source.executionEnabled());
        }

        Map<String, BidSourceState> storedStates = new LinkedHashMap<>();
        stateRepository.findAll().forEach(state -> storedStates.put(state.getSourceCode(), state));

        List<BidSourceStatusResponse> result = new ArrayList<>();
        enabledBySource.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BidSourceState state = storedStates.getOrDefault(
                            entry.getKey(), BidSourceState.builder().sourceCode(entry.getKey()).build()
                    );
                    result.add(BidSourceStatusResponse.from(state, entry.getValue()));
                });
        return List.copyOf(result);
    }

    private void validate(BidCollectionRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required.");
        }
        if (request.startDate().isAfter(request.endDate())) {
            throw new IllegalArgumentException("startDate must not be after endDate.");
        }
    }
}
