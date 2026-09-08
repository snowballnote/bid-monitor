package com.comhu.bidmonitor.performance;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/drive-index")
public class DriveFileIndexController {
    private final DriveFileIndexRefreshService refresh;
    public DriveFileIndexController(DriveFileIndexRefreshService refresh) { this.refresh = refresh; }
    @GetMapping
    public List<DriveFileIndexRefreshService.RootStatus> status() { return refresh.status(); }
    @PostMapping("/refresh")
    public List<DriveFileIndexRefreshService.RootStatus> refresh() { return refresh.refresh(); }
}
