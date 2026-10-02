package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.source.registration.BidSourceCatalogItem;
import com.comhu.bidmonitor.bid.source.registration.BidSourceCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bid-sources")
public class BidSourceCatalogController {

    private final BidSourceCatalogService catalogService;

    public BidSourceCatalogController(BidSourceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/catalog")
    public List<BidSourceCatalogItem> catalog() {
        return catalogService.catalog();
    }
}
