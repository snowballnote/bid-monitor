package com.comhu.bidmonitor.bid.settings;

import com.comhu.bidmonitor.bid.persistence.AllowedIndustryCodeRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AllowedIndustryCodeProvider {

    private static final Pattern CODE_PATTERN = Pattern.compile("[0-9]{4}");

    private final AllowedIndustryCodeRepository repository;

    public AllowedIndustryCodeProvider(AllowedIndustryCodeRepository repository) {
        this.repository = repository;
    }

    public Set<String> currentCodes() {
        Set<String> codes = repository.findAll();
        if (codes.isEmpty()) {
            throw new IllegalStateException("At least one allowed industry code must be configured.");
        }
        return immutableCopy(codes);
    }

    public Set<String> replaceCodes(Collection<String> requestedCodes) {
        Set<String> codes = normalize(requestedCodes);
        return immutableCopy(repository.replaceAll(codes));
    }

    private Set<String> normalize(Collection<String> requestedCodes) {
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed industry code is required.");
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String requestedCode : requestedCodes) {
            if (requestedCode == null || !CODE_PATTERN.matcher(requestedCode.trim()).matches()) {
                throw new IllegalArgumentException("Each allowed industry code must be exactly four digits.");
            }
            codes.add(requestedCode.trim());
        }
        return immutableCopy(codes);
    }

    private Set<String> immutableCopy(Collection<String> codes) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(codes));
    }
}
