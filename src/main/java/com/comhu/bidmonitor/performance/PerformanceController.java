package com.comhu.bidmonitor.performance;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@RestController
@RequestMapping("/api/performance-projects")
public class PerformanceController {
    private final PerformanceService service;
    private final PerformanceZipService zip;
    public PerformanceController(PerformanceService service, PerformanceZipService zip) {
        this.service = service; this.zip = zip;
    }

    @GetMapping
    public List<Project> projects() { return service.projects(); }
    @PostMapping
    public Project create(@RequestBody ProjectInput input) { return service.create(input); }
    @GetMapping("/{projectId}")
    public Project project(@PathVariable String projectId) { return service.project(projectId); }
    @PutMapping("/{projectId}")
    public Project updateProject(@PathVariable String projectId, @RequestBody ProjectInput input) {
        return service.updateProject(projectId, input);
    }
    @GetMapping("/{projectId}/entries")
    public List<Entry> entries(@PathVariable String projectId) { return service.entries(projectId); }
    @PostMapping("/{projectId}/import")
    public ImportResult paste(@PathVariable String projectId, @RequestBody PasteInput input) {
        return service.paste(projectId, input);
    }
    @PutMapping("/{projectId}/entries/{id}")
    public Entry update(@PathVariable String projectId, @PathVariable String id, @RequestBody EntryInput input) {
        return service.update(projectId, id, input);
    }
    @GetMapping("/{projectId}/entries/{id}/candidates")
    public Recommendations candidates(@PathVariable String projectId, @PathVariable String id) {
        return service.candidates(projectId, id);
    }
    @GetMapping("/{projectId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String projectId) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"performance-evidence.zip\"")
                .body(zip.download(projectId));
    }
}