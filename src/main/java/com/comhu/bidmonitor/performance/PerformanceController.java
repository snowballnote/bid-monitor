package com.comhu.bidmonitor.performance;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
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
    @PostMapping(value = "/{projectId}/entries/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Entry upload(@PathVariable String projectId, @PathVariable String id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("evidenceType") EvidenceType evidenceType) {
        return service.upload(projectId, id, file, evidenceType);
    }
    @PostMapping("/{projectId}/entries/{id}/evidences")
    public Entry addEvidence(@PathVariable String projectId, @PathVariable String id,
                             @RequestBody EvidenceInput input) {
        return service.addEvidence(projectId, id, input);
    }
    @PostMapping(value = "/{projectId}/entries/{id}/evidences/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Entry uploadEvidence(@PathVariable String projectId, @PathVariable String id,
                                @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                @RequestParam("evidenceType") EvidenceType evidenceType) {
        return service.uploadEvidence(projectId, id, file, evidenceType);
    }
    @PutMapping("/{projectId}/entries/{id}/evidences/{evidenceId}")
    public Entry replaceEvidence(@PathVariable String projectId, @PathVariable String id,
                                 @PathVariable String evidenceId, @RequestBody EvidenceInput input) {
        return service.replaceEvidence(projectId, id, evidenceId, input);
    }
    @PutMapping(value = "/{projectId}/entries/{id}/evidences/{evidenceId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Entry replaceEvidenceUpload(@PathVariable String projectId, @PathVariable String id,
                                       @PathVariable String evidenceId,
                                       @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                       @RequestParam("evidenceType") EvidenceType evidenceType) {
        return service.replaceEvidenceUpload(projectId, id, evidenceId, file, evidenceType);
    }
    @DeleteMapping("/{projectId}/entries/{id}/evidences/{evidenceId}")
    public Entry deleteEvidence(@PathVariable String projectId, @PathVariable String id,
                                @PathVariable String evidenceId) {
        return service.deleteEvidence(projectId, id, evidenceId);
    }
    @GetMapping("/{projectId}/entries/{id}/evidences/{evidenceId}/download")
    public ResponseEntity<InputStreamResource> downloadEvidence(@PathVariable String projectId,
                                                                 @PathVariable String id,
                                                                 @PathVariable String evidenceId) throws IOException {
        EvidenceDownload download = service.downloadEvidence(projectId, id, evidenceId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(download.content()));
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
