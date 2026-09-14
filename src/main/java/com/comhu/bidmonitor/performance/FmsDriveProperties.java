package com.comhu.bidmonitor.performance;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties("performance.drive")
public class FmsDriveProperties {
    private String baseUrl = "";
    private String loginId = "";
    private String password = "";
    private String company = "CNH";
    // Shared business folders to crawl; evidence folders below are search filters only.
    private List<String> indexRoots = new ArrayList<>();
    private List<String> certificateFolders = new ArrayList<>();
    private List<String> contractFolders = new ArrayList<>();
    private int maxDepth = -1;
    private int maxFolders = 100;
    private int maxFiles = 5000;
}
