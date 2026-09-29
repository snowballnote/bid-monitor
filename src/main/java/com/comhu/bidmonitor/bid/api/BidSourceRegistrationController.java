package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.api.dto.BidSourceRegistrationRequest;
import com.comhu.bidmonitor.bid.api.dto.BidSourceRegistrationResponse;
import com.comhu.bidmonitor.bid.api.dto.BidSourceReviewRequest;
import com.comhu.bidmonitor.bid.api.dto.BidSourceBindingRequest;
import com.comhu.bidmonitor.bid.api.dto.BidSourceActivationRequest;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.source.registration.BidSourceRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/bid-source-registrations")
public class BidSourceRegistrationController {

    private final BidSourceRegistrationService service;

    public BidSourceRegistrationController(BidSourceRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BidSourceRegistrationResponse> register(
            @RequestBody BidSourceRegistrationRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        request.rejectManagedFields();
        BidSourceRegistration registration = service.register(request.sourceName(), request.siteUrl());
        return ResponseEntity.created(URI.create(
                        "/api/bid-source-registrations/" + registration.getSourceId()))
                .body(BidSourceRegistrationResponse.from(registration));
    }

    @GetMapping
    public List<BidSourceRegistrationResponse> findAll() {
        return service.findAll().stream().map(BidSourceRegistrationResponse::from).toList();
    }

    @GetMapping("/{sourceId}")
    public BidSourceRegistrationResponse findById(@PathVariable long sourceId) {
        return BidSourceRegistrationResponse.from(service.findById(sourceId));
    }

    @PatchMapping("/{sourceId}/review")
    public BidSourceRegistrationResponse review(
            @PathVariable long sourceId,
            @RequestBody BidSourceReviewRequest request,
            Principal principal
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        request.rejectExecutionSetting();
        return BidSourceRegistrationResponse.from(service.review(
                sourceId,
                request.registrationStatus(),
                request.collectionMethod(),
                actor(principal)
        ));
    }

    @PostMapping("/{sourceId}/check")
    public BidSourceRegistrationResponse check(@PathVariable long sourceId) {
        return BidSourceRegistrationResponse.from(service.check(sourceId));
    }

    @PatchMapping("/{sourceId}/binding")
    public BidSourceRegistrationResponse bind(
            @PathVariable long sourceId,
            @RequestBody BidSourceBindingRequest request,
            Principal principal
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return BidSourceRegistrationResponse.from(
                service.bind(sourceId, request.sourceCode(), actor(principal))
        );
    }

    @PatchMapping("/{sourceId}/activation")
    public BidSourceRegistrationResponse activate(
            @PathVariable long sourceId,
            @RequestBody BidSourceActivationRequest request,
            Principal principal
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return BidSourceRegistrationResponse.from(
                service.activate(sourceId, request.requiredExecutionEnabled(), actor(principal))
        );
    }

    private String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalStateException("Authenticated administrator identity is required.");
        }
        return principal.getName();
    }
}
