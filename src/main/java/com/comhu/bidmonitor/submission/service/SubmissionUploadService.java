package com.comhu.bidmonitor.submission.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

@Service
public class SubmissionUploadService {
    private final JdbcTemplate jdbc;
    private final Path root;
    private final SubmissionPersonnelService personnel;
    public SubmissionUploadService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
            @Value("${submissions.upload-directory:./data/submission-uploads}") String directory, SubmissionPersonnelService personnel) {
        this.jdbc=jdbc;this.root=Path.of(directory).toAbsolutePath().normalize();this.personnel=personnel;
    }
    public record Uploaded(String id,String originalFilename,long sizeBytes) { }
    private Path path(String id) { return root.resolve(UUID.fromString(id).toString()+".bin"); }
    @Transactional
    public Uploaded upload(String masterId,MultipartFile input) {
        if(input==null||input.isEmpty()||input.getSize()>20L*1024*1024)throw new IllegalArgumentException("비어 있지 않은 20MB 이하 파일을 선택하세요.");
        var refs=jdbc.queryForList("SELECT source_reference FROM submission_document_master WHERE id=? AND active=TRUE AND category='COMPANY_COMMON' FOR UPDATE",String.class,masterId);
        if(refs.isEmpty())throw new SubmissionNotFoundException("회사 공통서류를 찾을 수 없습니다.");
        String name=Optional.ofNullable(input.getOriginalFilename()).orElse("document").replace((char)92,'/');
        name=name.substring(name.lastIndexOf('/')+1);
        var clean=new StringBuilder();for(char c:name.toCharArray())clean.append(Character.isISOControl(c)?'_':c);name=clean.toString().strip();
        if(name.isBlank()||name.length()>2000)throw new IllegalArgumentException("파일명을 확인하세요.");
        String id=UUID.randomUUID().toString();Path target=path(id);
        try {
            Files.createDirectories(root);
            try(var in=input.getInputStream();var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) { in.transferTo(out); }
        } catch(IOException failure) {
            try { Files.deleteIfExists(target); } catch(IOException ignored) { }
            throw new IllegalStateException("파일 저장에 실패했습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){ }
            }
        });
        jdbc.update("INSERT INTO submission_uploaded_file(id,original_filename,size_bytes) VALUES (?,?,?)",id,name,input.getSize());
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
        int updated=jdbc.update("UPDATE submission_common_document SET uploaded_file_id=?,file_id=NULL,file_public_id=NULL,original_filename=?,file_ext=?,issued_at=NULL,expires_at=NULL,active=TRUE,updated_at=CURRENT_TIMESTAMP WHERE document_type=?",id,name,ext,refs.getFirst());
        if(updated==0)jdbc.update("INSERT INTO submission_common_document(document_type,display_name,uploaded_file_id,original_filename,file_ext,refresh_policy,active,created_at,updated_at) VALUES (?,?,?,?,?,'NONE',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",refs.getFirst(),name,id,name,ext);
        return new Uploaded(id,name,input.getSize());
    }
    public StreamingResponseBody zip(Long caseId) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM submission_case WHERE id=?",Long.class,caseId)==0)throw new SubmissionNotFoundException("프로젝트를 찾을 수 없습니다.");
        var rows=jdbc.queryForList("SELECT requirement_id,uploaded_file_id,original_filename FROM submission_document_selection WHERE submission_case_id=? ORDER BY requirement_id,id",caseId);
        var personnelFiles=personnel.collected(caseId);
        if(rows.isEmpty() && personnelFiles.isEmpty())throw new IllegalArgumentException("모은 파일이 없습니다.");
        for(var row:rows) {
            if(row.get("uploaded_file_id")==null)throw new IllegalArgumentException("기존 회사 파일 참조가 포함되어 있습니다. 서류 관리에서 파일을 업로드하고 다시 모아주세요.");
            if(!Files.isRegularFile(path(row.get("uploaded_file_id").toString())))throw new IllegalStateException("저장된 파일을 찾을 수 없습니다.");
        }
        return output->{
            try(var zip=new ZipOutputStream(output)) {
                for(var row:rows) {
                    String filename=row.get("original_filename").toString().replace('/','_').replace((char)92,'_');
                    zip.putNextEntry(new ZipEntry(row.get("requirement_id")+"_"+filename));
                    Files.copy(path(row.get("uploaded_file_id").toString()),zip);zip.closeEntry();
                }
                personnel.appendZip(zip,personnelFiles);
            }
        };
    }
}
