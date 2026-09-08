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
    private String sessionToken = "";
    private String company = "CNH";
    private List<String> certificateFolders = new ArrayList<>();
    private List<String> contractFolders = new ArrayList<>();
    private int maxDepth = 3;
    private int maxFolders = 100;
    private int maxFiles = 5000;
}