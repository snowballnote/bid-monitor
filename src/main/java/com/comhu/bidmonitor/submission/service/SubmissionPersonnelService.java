package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.performance.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Project-local people and explicit file choices. Remote sources are read-only. */
@Service
public class SubmissionPersonnelService {
    public enum Type {
        PROFILE("프로필", List.of("프로필", "이력서", "profile")),
        QUALIFICATION("자격사본", List.of("자격")),
        KOSA("KOSA 경력증명서", List.of("kosa", "경력증명서", "소프트웨어기술자경력증명", "소프트웨어기술경력증")),
        PIA("개인정보 영향평가 전문인력 인증서", List.of("전문인력인증", "전문인력인정", "영향평가인증", "pia인증"));
        public final String label;
        final List<String> terms;
        Type(String label, List<String> terms) { this.label = label; this.terms = terms; }
    }
    public record Person(String id, String name, String department, List<Document> documents) { }
    public record PersonOption(String name, String department) { }
    public record Document(Type type, String label, boolean needed, String filename, String source,
                           LocalDate filenameDate, String latestStatus, String fmsReferenceId) { }
    public record Candidate(String id, String filename, LocalDate filenameDate, boolean recommended, String note) { }
    private record Saved(Type type, boolean needed, String driveId, String uploadId, String filename) { }
    private final JdbcTemplate jdbc;
    private final FmsDriveProperties properties;
    private final PerformanceDriveFileRepository references;
    private final FmsDrivePort drive;
    private final Path uploadRoot;
    private static final Pattern DATE = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})[. _-]?(0?[1-9]|1[0-2])[. _-]?(0?[1-9]|[12]\\d|3[01])(?!\\d)");

    public SubmissionPersonnelService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc, FmsDriveProperties properties,
            PerformanceDriveFileRepository references, FmsDrivePort drive,
            @Value("${submissions.upload-directory:./data/submission-uploads}") String directory) {
        this.jdbc = jdbc; this.properties = properties; this.references = references; this.drive = drive;
        this.uploadRoot = Path.of(directory).toAbsolutePath().normalize();
    }
    public static LocalDate filenameDate(String name) {
        if (name == null) return null;
        var matcher = DATE.matcher(name); LocalDate latest = null;
        while (matcher.find()) {
            try {
                var date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
                if (latest == null || date.isAfter(latest)) latest = date;
            } catch (java.time.DateTimeException ignored) { }
        }
        return latest;
    }
    private static String compact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static final Set<String> PERSONNEL_ROOTS = Set.of("/컴앤휴먼_감리_인력", "/컴앤휴먼_개인정보_인력",
            "/컴앤휴먼_인공지능본부_인력", "/컴앤휴먼_컨설팅_인력");
    private static String[] pathParts(String path) { return path.replace('\\', '/').split("/"); }
    private static boolean personnelPath(String path) {
        String normalized = Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFKC);
        return PERSONNEL_ROOTS.stream().anyMatch(root -> normalized.startsWith(root + "/"));
    }
    private static Set<Type> keywordTypes(String text) {
        String normalized = compact(text);
        Set<Type> types = EnumSet.noneOf(Type.class);
        for (Type type : Type.values()) if (type.terms.stream().anyMatch(normalized::contains)) types.add(type);
        return types;
    }
    private static Type documentType(FmsDrivePort.Item file) {
        // Explicit filename labels win over generic enclosing folders (e.g. KOSA in 자격사본).
        Set<Type> types = keywordTypes(file.name());
        if (!types.isEmpty()) return types.size() == 1 ? types.iterator().next() : null;
        String[] parts = pathParts(file.path());
        for (int i = parts.length - 2; i >= 0; i--) {
            types = keywordTypes(parts[i]);
            if (!types.isEmpty()) return types.size() == 1 ? types.iterator().next() : null;
        }
        return null;
    }
    public static String extractName(String filename) {
        String text = Normalizer.normalize(filename, Normalizer.Form.NFKC);
        int dot = text.lastIndexOf('.');
        if (dot >= 0) text = text.substring(0, dot);
        // Remove known document labels before splitting, including attached label/name forms.
        var labels = new ArrayList<String>(List.of("개인정보 영향평가 전문인력 인증서", "개인정보영향평가전문인력인증서",
                "개인정보", "영향평가", "전문인력", "인증서", "인정서", "경력증명서", "경력증명", "자격증사본", "자격사본",
                "자격증", "경력", "자격", "프로파일", "프로필", "이력서", "KOSA", "profile", "최종본", "최종", "수정본", "수정", "사본", "원본", "최신", "날짜없음", "양식", "서식", "샘플", "작성예시", "예시"));
        labels.sort(Comparator.comparingInt(String::length).reversed());
        for (String label : labels) text = text.replaceAll("(?i)" + Pattern.quote(label), " ");
        var names = new LinkedHashSet<String>();
        for (String token : text.split("[^가-힣]+")) {
            if (token.matches("[가-힣]{2,4}") && "김이박최정강조윤장임한오서신권황안송전홍유고문양손배백허남심노하곽성차주우구민류나진지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용".indexOf(token.charAt(0)) >= 0)
                names.add(token);
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }
    private static String department(String path) {
        String[] parts = pathParts(path);
        // Only explicit organizational labels are treated as departments.
        for (int i = parts.length - 2; i >= 0; i--) {
            if (!keywordTypes(parts[i]).isEmpty()) continue;
            String label = parts[i].replaceFirst("^\\d+[._ -]*", "").strip();
            if (label.matches("[가-힣A-Za-z0-9 ]{1,80}(팀|본부|부서|사업부|센터|연구소|그룹|실|처)")) return label;
        }
        return "";
    }
    private static boolean matches(FmsDrivePort.Item file, String name, Type type) {
        return documentType(file) == type && name.equals(extractName(file.name()));
    }
    private String origin() { return properties.getBaseUrl().replaceAll("/+$", ""); }
    private List<FmsDrivePort.Item> indexedFiles() {
        if (origin().isBlank()) throw new FmsDriveException("FMS 파일 인덱스 연결을 확인하세요. 직접 업로드도 가능합니다.");
        var states = jdbc.queryForList("SELECT root,status FROM drive_index_root_state WHERE source=? AND company=?", origin(), properties.getCompany())
                .stream().filter(row -> {
                    String root = row.get("root").toString().replaceAll("/+$", "");
                    return PERSONNEL_ROOTS.stream().anyMatch(scope -> scope.equals(root) || scope.startsWith(root + "/") || root.startsWith(scope + "/"));
                }).toList();
        if (states.isEmpty() || states.stream().anyMatch(row -> !"SUCCESS".equals(row.get("status"))))
            throw new FmsDriveException("FMS 인덱스가 미구축·갱신 중이거나 조회에 실패했습니다. 직접 업로드도 가능합니다.");
        var files = jdbc.query("SELECT DISTINCT name,path,size,last_modified FROM drive_file_index WHERE source=? AND company=? ORDER BY path",
                (rs, n) -> new FmsDrivePort.Item(rs.getString("name"), rs.getString("path"), false,
                        rs.getLong("size"), rs.getTimestamp("last_modified") == null ? null : rs.getTimestamp("last_modified").toInstant()),
                origin(), properties.getCompany());
        var scoped = files.stream().filter(file -> personnelPath(file.path())).toList();
        if (states.stream().anyMatch(row -> !"SUCCESS".equals(row.get("status")) && scoped.stream().anyMatch(file ->
                file.path().replace('\\', '/').startsWith(row.get("root").toString().replaceAll("/+$", "") + "/"))))
            throw new FmsDriveException("인력 파일 인덱스 갱신 상태를 확인하세요. 직접 업로드도 가능합니다.");
        return scoped;
    }
    private void project(Long id, boolean lock) {
        if (jdbc.queryForList("SELECT id FROM submission_case WHERE id=?" + (lock ? " FOR UPDATE" : ""), Long.class, id).isEmpty())
            throw new SubmissionNotFoundException("프로젝트를 찾을 수 없습니다.");
    }
    private String personName(Long caseId, String personId) {
        return jdbc.queryForList("SELECT name FROM submission_person WHERE id=? AND submission_case_id=?", String.class, personId, caseId)
                .stream().findFirst().orElseThrow(() -> new SubmissionNotFoundException("프로젝트 인력을 찾을 수 없습니다."));
    }
    private void touch(Long caseId) { jdbc.update("UPDATE submission_case SET updated_at=CURRENT_TIMESTAMP WHERE id=?", caseId); }
    private List<Saved> saved(String personId) {
        return jdbc.query("SELECT * FROM submission_person_document WHERE person_id=? ORDER BY document_type", (rs, n) ->
                new Saved(Type.valueOf(rs.getString("document_type")), rs.getBoolean("needed"), rs.getString("drive_file_id"),
                        rs.getString("uploaded_file_id"), rs.getString("filename")), personId);
    }
    public List<Person> list(Long caseId) {
        project(caseId, false);
        List<FmsDrivePort.Item> files;
        try { files = indexedFiles(); } catch (FmsDriveException unavailable) { files = null; }
        final var index = files;
        return jdbc.query("SELECT id,name,department FROM submission_person WHERE submission_case_id=? ORDER BY created_at,id", (rs, n) -> {
            String id = rs.getString("id"), name = rs.getString("name");
            var documents = saved(id).stream().map(row -> {
                String latest = "파일 미등록";
                if (row.filename() != null) {
                    var date = filenameDate(row.filename());
                    var newest = index == null ? null : index.stream().filter(file -> matches(file, name, row.type()))
                            .map(file -> filenameDate(file.name())).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                    latest = row.type() == Type.PIA ? "기존 파일 · 직접 확인" : index == null ? "최신 여부 확인 필요"
                            : date == null ? "파일명 날짜 없음" : newest == null ? "비교 후보 없음 · 직접 확인" : newest.isAfter(date) ? "더 최신 후보 있음" : "파일명 날짜 기준 최신";
                }
                return new Document(row.type(), row.type().label, row.needed(), row.filename(), row.uploadId() != null ? "PC" : row.driveId() != null ? "FMS" : null,
                        filenameDate(row.filename()), latest, row.filename() != null && row.uploadId() == null ? row.driveId() : null);
            }).toList();
            return new Person(id, name, rs.getString("department"), documents);
        }, caseId);
    }
    public List<PersonOption> search(Long caseId, String query) {
        project(caseId, false);
        String term = query == null ? "" : query.strip();
        if (term.isEmpty() || term.length() > 100) return List.of();
        var departments = new TreeMap<String, Set<String>>();
        for (var file : indexedFiles()) {
            String name = extractName(file.name());
            if (name == null || !name.contains(term)) continue;
            var found = departments.computeIfAbsent(name, ignored -> new TreeSet<>());
            String department = department(file.path());
            if (!department.isEmpty()) found.add(department);
        }
        return departments.entrySet().stream().map(entry -> new PersonOption(entry.getKey(),
                entry.getValue().size() == 1 ? entry.getValue().iterator().next() : "")).toList();
    }
    @Transactional
    public List<Person> add(Long caseId, String selectedName, String selectedDepartment) {
        project(caseId, true);
        if (selectedName == null || selectedName.isBlank()) throw new IllegalArgumentException("인력 검색 결과에서 이름을 선택하세요.");
        var employee = search(caseId, selectedName).stream().filter(person -> person.name().equals(selectedName)
                && person.department().equals(selectedDepartment == null ? "" : selectedDepartment)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("현재 인력 파일 인덱스의 검색 결과에서 다시 선택하세요."));
        String name = employee.name(), department = employee.department();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM submission_person WHERE submission_case_id=? AND name=?", Long.class, caseId, name) > 0)
            throw new IllegalArgumentException("이미 추가한 인력입니다.");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO submission_person(id,submission_case_id,name,department) VALUES(?,?,?,?)", id, caseId, name, department);
        for (var type : Type.values()) jdbc.update("INSERT INTO submission_person_document(person_id,document_type) VALUES(?,?)", id, type.name());
        touch(caseId); return list(caseId);
    }
    @Transactional
    public List<Person> remove(Long caseId, String personId) {
        project(caseId, true); personName(caseId, personId);
        jdbc.update("DELETE FROM submission_person WHERE id=?", personId); touch(caseId); return list(caseId);
    }
    @Transactional
    public List<Person> needed(Long caseId, String personId, Type type, boolean needed) {
        project(caseId, true); personName(caseId, personId);
        // Unchecking excludes this document from collection but preserves its saved file.
        jdbc.update("UPDATE submission_person_document SET needed=? WHERE person_id=? AND document_type=?", needed, personId, type.name());
        touch(caseId); return list(caseId);
    }
    // Candidate IDs for unregistered files are opaque, stable lookup keys, not stored references.
    // Selection always resolves them against the current scoped index before registering a file.
    private String indexedCandidateId(FmsDrivePort.Item file) {
        String key = origin() + "\0" + properties.getCompany() + "\0" + file.path();
        try {
            return "index-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
    private Map<String, String> registeredCandidateIds() {
        Map<String, String> ids = new HashMap<>();
        jdbc.query("SELECT drive_path,id FROM performance_drive_file WHERE origin=? AND company=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.put(rs.getString("drive_path"), rs.getString("id")),
                origin(), properties.getCompany());
        return ids;
    }
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Long caseId, String personId, Type type) {
        String name = personName(caseId, personId);
        var files = indexedFiles().stream().filter(file -> matches(file, name, type))
                .sorted(type == Type.PIA ? Comparator.comparing(FmsDrivePort.Item::name) :
                        Comparator.comparing((FmsDrivePort.Item file) -> filenameDate(file.name()), Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(FmsDrivePort.Item::name)).toList();
        LocalDate newest = files.stream().map(file -> filenameDate(file.name())).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        var registeredIds = registeredCandidateIds();
        return files.stream().map(file -> {
            LocalDate date = filenameDate(file.name());
            boolean recommended = type != Type.PIA && date != null && date.equals(newest);
            return new Candidate(registeredIds.getOrDefault(file.path(), indexedCandidateId(file)), file.name(), date, recommended,
                    type == Type.PIA ? "기존 인증서 · 내용 확인 후 선택" : recommended ? "파일명 날짜 기준 최신 후보" : date == null ? "파일명 날짜 없음 · 직접 확인" : "이전 날짜 후보");
        }).toList();
    }
    @Transactional
    public List<Person> select(Long caseId, String personId, Type type, String candidateId) {
        project(caseId, true);
        String name = personName(caseId, personId);
        if (candidateId == null) jdbc.update("UPDATE submission_person_document SET drive_file_id=NULL,uploaded_file_id=NULL,filename=NULL WHERE person_id=? AND document_type=?", personId, type.name());
        else {
            var registeredIds = registeredCandidateIds();
            var candidate = indexedFiles().stream().filter(file -> matches(file, name, type))
                    .filter(file -> candidateId.equals(indexedCandidateId(file)) || candidateId.equals(registeredIds.get(file.path())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("현재 인력과 서류 유형에 맞는 후보를 선택하세요."));
            var ref = references.register(origin(), properties.getCompany(), candidate);
            jdbc.update("UPDATE submission_person_document SET drive_file_id=?,uploaded_file_id=NULL,filename=? WHERE person_id=? AND document_type=?",
                    ref.id(), candidate.name(), personId, type.name());
        }
        touch(caseId); return list(caseId);
    }
    @Transactional
    public List<Person> upload(Long caseId, String personId, Type type, MultipartFile input) {
        project(caseId, true); personName(caseId, personId);
        if (input == null || input.isEmpty() || input.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("비어 있지 않은 20MB 이하 파일을 선택하세요.");
        String name = Optional.ofNullable(input.getOriginalFilename()).orElse("").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "_").strip();
        if (name.isBlank() || name.length() > 2000) throw new IllegalArgumentException("파일명을 확인하세요.");
        String id = UUID.randomUUID().toString(); Path target = uploadRoot.resolve(id + ".bin");
        try {
            Files.createDirectories(uploadRoot);
            try (var in = input.getInputStream(); var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { in.transferTo(out); }
        } catch (IOException failure) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw new IllegalStateException("파일 저장에 실패했습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            }
        });
        jdbc.update("INSERT INTO submission_uploaded_file(id,original_filename,size_bytes) VALUES(?,?,?)", id, name, input.getSize());
        jdbc.update("UPDATE submission_person_document SET uploaded_file_id=?,drive_file_id=NULL,filename=? WHERE person_id=? AND document_type=?", id, name, personId, type.name());
        touch(caseId); return list(caseId);
    }
    public record CollectedFile(String entryName, String uploadId, String driveId) { }
    public List<CollectedFile> collected(Long caseId) {
        return jdbc.query("SELECT p.id,p.name,d.document_type,d.filename,d.uploaded_file_id,d.drive_file_id FROM submission_person p JOIN submission_person_document d ON p.id=d.person_id WHERE p.submission_case_id=? AND d.needed=TRUE AND d.filename IS NOT NULL ORDER BY p.id,d.document_type",
                (rs, n) -> new CollectedFile("인력/" + safe(rs.getString("name")) + "_" + rs.getString("id") + "/" + rs.getString("document_type") + "_" + safe(rs.getString("filename")),
                        rs.getString("uploaded_file_id"), rs.getString("drive_file_id")), caseId);
    }
    private static String safe(String name) { return name.replaceAll("[\\p{Cntrl}/\\\\]", "_").replace("..", "_"); }
    public void appendZip(ZipOutputStream zip, List<CollectedFile> files) throws IOException {
        for (var file : files) {
            InputStream input;
            if (file.uploadId() != null) input = Files.newInputStream(uploadRoot.resolve(UUID.fromString(file.uploadId()) + ".bin"));
            else {
                var ref = references.find(file.driveId());
                if (!ref.origin().equals(origin()) || !ref.company().equals(properties.getCompany())
                        || indexedFiles().stream().noneMatch(item -> item.path().equals(ref.path())))
                    throw new IllegalArgumentException("현재 FMS 인덱스의 파일인지 다시 확인하세요.");
                if (!drive.canDownload(ref.path())) throw new FmsDriveException("선택한 FMS 파일의 다운로드 권한이 없습니다.", true);
                input = drive.download(ref.path());
            }
            try (input) { zip.putNextEntry(new ZipEntry(file.entryName())); input.transferTo(zip); zip.closeEntry(); }
        }
    }
}
