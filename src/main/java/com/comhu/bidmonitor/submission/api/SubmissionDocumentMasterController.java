package com.comhu.bidmonitor.submission.api;
import com.comhu.bidmonitor.submission.service.SubmissionDocumentMasterService;
import com.comhu.bidmonitor.submission.service.SubmissionDocumentMasterService.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/submission-document-masters")
public class SubmissionDocumentMasterController {
    private final SubmissionDocumentMasterService service;
    private final com.comhu.bidmonitor.submission.service.SubmissionUploadService uploads;
    private final com.comhu.bidmonitor.submission.service.SubmissionCommonFileService files;
    public SubmissionDocumentMasterController(SubmissionDocumentMasterService service, com.comhu.bidmonitor.submission.service.SubmissionCommonFileService files, com.comhu.bidmonitor.submission.service.SubmissionUploadService uploads) { this.uploads=uploads; this.service = service; this.files=files; }
    public record FileInput(Long fileId) { }
    @GetMapping("/{id}/candidates") public List<com.comhu.bidmonitor.submission.service.SubmissionCommonFileService.Candidate> candidates(@PathVariable String id) { return files.candidates(id); }
    @PutMapping("/{id}/current-file") public com.comhu.bidmonitor.submission.service.SubmissionCommonFileService.Current save(@PathVariable String id,@RequestBody FileInput input) { return files.save(id,input.fileId()); }
    @PostMapping(value="/{id}/upload",consumes="multipart/form-data")
    public com.comhu.bidmonitor.submission.service.SubmissionUploadService.Uploaded upload(@PathVariable String id,@RequestParam("file") org.springframework.web.multipart.MultipartFile file) { return uploads.upload(id,file); }
    @GetMapping public List<Item> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Item create(@RequestBody Input input) { return service.create(input); }
    @PutMapping("/{id}") public Item update(@PathVariable String id, @RequestBody Input input) { return service.update(id,input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { service.delete(id); }
}
