package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Current references and project snapshots live exclusively in Biz Assist H2. */
@Service
public class SubmissionCommonFileService {
    private final JdbcTemplate jdbc;
    private final CompanyFileSearchPort files;
    private final SubmissionCaseService cases;
    private final SubmissionCaseRepository projects;
    private final SubmissionRequirementExtractor extractor;
    public SubmissionCommonFileService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc, CompanyFileSearchPort files,
            SubmissionCaseService cases, SubmissionCaseRepository projects, SubmissionRequirementExtractor extractor) {
        this.jdbc=jdbc; this.files=files; this.cases=cases; this.projects=projects; this.extractor=extractor;
    }
    public record Candidate(Long fileId,String originalFilename,String fileExt) { }
    public record Current(Long fileId,String publicId,String originalFilename,String fileExt,String uploadedFileId) { }
    public record CollectionResult(List<Long> missingRequirementIds) { }
    private record Master(String reference,String name) { }
    private Master master(String id, boolean lock) {
        return jdbc.query("SELECT source_reference,name FROM submission_document_master WHERE id=? AND active=TRUE AND category='COMPANY_COMMON'"+(lock?" FOR UPDATE":""),
                (rs,n)->new Master(rs.getString(1),rs.getString(2)),id).stream().findFirst()
                .orElseThrow(()->new SubmissionNotFoundException("회사 공통서류를 찾을 수 없습니다."));
    }
    public List<Candidate> candidates(String id) {
        var master=master(id,false);
        return files.searchByKeywords(extractor.searchKeywords(master.name()),50).stream()
                .map(f->new Candidate(f.fileId(),f.originalFilename(),f.fileExt())).toList();
    }
    @Transactional
    public Current save(String id,Long fileId) {
        if(fileId==null||fileId<=0)throw new IllegalArgumentException("저장할 파일을 선택하세요.");
        var master=master(id,true);
        var file=files.findActiveFileById(fileId).orElseThrow(()->new InvalidSubmissionSelectionException("선택한 활성 파일을 찾을 수 없습니다."));
        if(file.publicId()==null)throw new InvalidSubmissionSelectionException("파일 참조 정보를 확인할 수 없습니다.");
        int updated=jdbc.update("UPDATE submission_common_document SET uploaded_file_id=NULL,file_id=?,file_public_id=?,original_filename=?,file_ext=?,issued_at=NULL,expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE document_type=?",
                file.fileId(),file.publicId()==null?null:file.publicId().toString(),file.originalFilename(),file.fileExt(),master.reference());
        if(updated==0)jdbc.update("INSERT INTO submission_common_document(document_type,display_name,file_id,file_public_id,original_filename,file_ext,refresh_policy,active,created_at,updated_at) VALUES (?,?,?,?,?,?,'NONE',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                master.reference(),master.name(),file.fileId(),file.publicId()==null?null:file.publicId().toString(),file.originalFilename(),file.fileExt());
        return new Current(file.fileId(),file.publicId()==null?null:file.publicId().toString(),file.originalFilename(),file.fileExt(),null);
    }
    private List<String> references(SubmissionDocumentRequirement requirement, boolean activeOnly) {
        return jdbc.query("SELECT source_reference FROM submission_document_master WHERE category='COMPANY_COMMON' "+(activeOnly?"AND active=TRUE ":"")+
                "AND (source_reference=? OR (name=? AND requirement_category=?)) ORDER BY CASE WHEN source_reference=? THEN 0 ELSE 1 END",
                (rs,n)->rs.getString(1),requirement.getSourceReference(),requirement.getDocumentName(),requirement.getCategory().name(),requirement.getSourceReference());
    }
    public boolean isCommon(SubmissionDocumentRequirement requirement) { return !references(requirement,false).isEmpty(); }
    @Transactional
    public CollectionResult collect(Long caseId,List<SubmissionCaseService.ManualRequirement> requested) {
        projects.lock(caseId);
        var requirements=cases.replaceRequirements(caseId,requested);
        var missing=new java.util.ArrayList<Long>();
        for(var requirement:requirements) {
            if(!isCommon(requirement))continue;
            var refs=references(requirement,true);
            var current=refs.isEmpty()?List.<Current>of():jdbc.query("SELECT file_id,file_public_id,original_filename,file_ext,uploaded_file_id FROM submission_common_document WHERE document_type=? AND active=TRUE AND (file_id IS NOT NULL OR uploaded_file_id IS NOT NULL)",
                    (rs,n)->new Current(rs.getObject(1,Long.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),refs.getFirst());
            if(current.isEmpty()) { missing.add(requirement.getId()); continue; }
            var file=current.getFirst();
            // Unchanged references keep their selection IDs; no company DB query is needed to collect.
            var existing=jdbc.queryForList("SELECT file_id,uploaded_file_id FROM submission_document_selection WHERE submission_case_id=? AND requirement_id=?",caseId,requirement.getId());
            if(existing.size()==1 && java.util.Objects.equals(existing.getFirst().get("file_id"),file.fileId()) && java.util.Objects.equals(existing.getFirst().get("uploaded_file_id"),file.uploadedFileId()))continue;
            jdbc.update("DELETE FROM submission_document_selection WHERE submission_case_id=? AND requirement_id=?",caseId,requirement.getId());
            jdbc.update("INSERT INTO submission_document_selection(submission_case_id,requirement_id,file_id,file_public_id,original_filename,file_ext,uploaded_file_id,selected_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    caseId,requirement.getId(),file.fileId(),file.publicId(),file.originalFilename(),file.fileExt(),file.uploadedFileId());
        }
        return new CollectionResult(missing);
    }
}
