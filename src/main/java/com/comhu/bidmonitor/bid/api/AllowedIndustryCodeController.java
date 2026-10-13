package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.api.dto.AllowedIndustryCodesRequest;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/settings/allowed-industry-codes")
public class AllowedIndustryCodeController {

    private final AllowedIndustryCodeProvider provider;

    public AllowedIndustryCodeController(AllowedIndustryCodeProvider provider) {
        this.provider = provider;
    }

    @GetMapping
    public List<String> getCodes() {
        return List.copyOf(provider.currentCodes());
    }

    @PutMapping
    public List<String> replaceCodes(@RequestBody AllowedIndustryCodesRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return List.copyOf(provider.replaceCodes(request.codes()));
    }
}
