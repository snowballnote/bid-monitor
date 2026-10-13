package com.comhu.bidmonitor.bid.persistence;

import java.util.Set;

public interface AllowedIndustryCodeRepository {

    Set<String> findAll();

    Set<String> replaceAll(Set<String> codes);
}
