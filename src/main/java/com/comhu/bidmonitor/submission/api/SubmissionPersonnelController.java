package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.service.SubmissionPersonnelService;
import com.comhu.bidmonitor.submission.service.SubmissionPersonnelService.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/submission-cases/{caseId}/people")
public class SubmissionPersonnelController {
    private final SubmissionPersonnelService service;
    public SubmissionPersonnelController(SubmissionPersonnelService service) { this.service = service; }
    public record AddPerson(String name, String department) { }
    public record Needed(boolean needed) { }
    public record Selection(String candidateId) { }
    @GetMapping public List<Person> list(@PathVariable Long caseId) { return service.list(caseId); }
    @GetMapping("/search") public List<PersonOption> search(@PathVariable Long caseId, @RequestParam String q) { return service.search(caseId, q); }
    @PostMapping public List<Person> add(@PathVariable Long caseId, @RequestBody AddPerson body) { return service.add(caseId, body.name(), body.department()); }
    @DeleteMapping("/{personId}") public List<Person> remove(@PathVariable Long caseId, @PathVariable String personId) { return service.remove(caseId, personId); }
    @PutMapping("/{personId}/documents/{type}") public List<Person> needed(@PathVariable Long caseId, @PathVariable String personId, @PathVariable Type type, @RequestBody Needed body) { return service.needed(caseId, personId, type, body.needed()); }
    @GetMapping("/{personId}/documents/{type}/candidates") public List<Candidate> candidates(@PathVariable Long caseId, @PathVariable String personId, @PathVariable Type type) { return service.candidates(caseId, personId, type); }
    @PutMapping("/{personId}/documents/{type}/selection") public List<Person> select(@PathVariable Long caseId, @PathVariable String personId, @PathVariable Type type, @RequestBody Selection body) { return service.select(caseId, personId, type, body.candidateId()); }
    @PostMapping("/{personId}/documents/{type}/upload") public List<Person> upload(@PathVariable Long caseId, @PathVariable String personId, @PathVariable Type type, @RequestParam MultipartFile file) { return service.upload(caseId, personId, type, file); }
}
