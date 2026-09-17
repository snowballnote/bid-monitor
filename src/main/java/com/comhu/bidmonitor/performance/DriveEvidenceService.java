package com.comhu.bidmonitor.performance;

import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class DriveEvidenceService {
    private final FmsDrivePort drive;
    private final FmsDriveProperties properties;
    private final PerformanceDriveFileRepository references;
    private final DriveFileIndexRepository index;
    public DriveEvidenceService(FmsDrivePort drive, FmsDriveProperties properties, PerformanceDriveFileRepository references, DriveFileIndexRepository index) {
        this.drive = drive; this.properties = properties; this.references = references; this.index = index;
    }

    private record Match(FmsDrivePort.Item item, double score, String reason) { }
    private record Recommended(EvidenceType type, List<Match> matches, String nextAction) { }

    public Recommendations recommend(Entry entry) {
        var recommended = recommended(entry);
        return new Recommendations(recommended.matches().stream().map(match -> candidate(recommended.type(), match)).toList(),
                recommended.nextAction());
    }

    private Recommended recommended(Entry entry) {
        if (entry.resolvedStatus() == BusinessStatus.COMPLETED) {
            var certificates = matches(entry, EvidenceType.CERTIFICATE, properties.getCertificateFolders());
            if (!certificates.isEmpty()) return new Recommended(EvidenceType.CERTIFICATE, certificates, "실적증명서 후보 확인 후 직접 선택");
        }
        var contracts = matches(entry, EvidenceType.CONTRACT, properties.getContractFolders());
        return new Recommended(EvidenceType.CONTRACT, contracts, contracts.isEmpty() ? "KITC 요청 필요" : "계약서 후보 확인 후 직접 선택");
    }

    private List<Match> matches(Entry entry, EvidenceType type, List<String> roots) {
        if (roots == null || roots.isEmpty() || roots.stream().allMatch(String::isBlank)) {
            throw new FmsDriveException(type.label + " 검색 폴더를 설정하세요.");
        }
        Set<String> seenFiles = new HashSet<>();
        List<Match> matches = new ArrayList<>();
        for (String root : roots.stream().filter(value -> !value.isBlank())
                .map(FmsDriveHttpAdapter::normalizedPath).distinct().toList()) {
            for (var item : index.filesUnderFolder(origin(), properties.getCompany(), root)) {
                if (!seenFiles.add(item.path()) || evidenceType(item.name()) != type) continue;
                Match match = match(entry.info(), item);
                if (match != null) matches.add(match);
            }
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(match -> match.item().lastModified(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(match -> match.item().path()))
                .limit(30).toList();
    }

    private Candidate candidate(EvidenceType type, Match match) {
        var item = match.item();
        String id = references.findByLocation(origin(), properties.getCompany(), item.path())
                .map(PerformanceDriveFileRepository.Reference::id).orElseGet(() -> candidateId(item.path()));
        int dot = item.name().lastIndexOf('.');
        String ext = dot < 0 ? "" : item.name().substring(dot + 1);
        return new Candidate(new EvidenceFile(null, id, item.name(), ext, item.size(), item.lastModified()), type, match.reason());
    }

    private String candidateId(String path) {
        String identity = origin() + "\0" + properties.getCompany() + "\0" + path;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static EvidenceType evidenceType(String name) {
        String normalized = compact(name);
        if (normalized.contains("실적증명서") || normalized.contains("실적증명원")) return EvidenceType.CERTIFICATE;
        return normalized.contains("계약서") ? EvidenceType.CONTRACT : null;
    }

    private Match match(EntryInput input, FmsDrivePort.Item item) {
        String filename = compact(item.name());
        String business = compact(input.businessName());
        String client = compact(input.client());
        List<String> tokens = Arrays.stream(Normalizer.normalize(input.businessName(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 2 && !token.matches("(19|20)\\d{2}년?"))
                .filter(token -> !Set.of("사업", "용역", "프로젝트", "수립").contains(token)).distinct().toList();
        boolean exactName = !business.isEmpty() && filename.contains(business);
        boolean clientMatch = !client.isEmpty() && filename.contains(client);
        long hits = tokens.stream().filter(filename::contains).count();
        double coverage = tokens.isEmpty() ? 0 : (double) hits / tokens.size();
        if (!exactName && (hits < Math.min(2, tokens.size()) || tokens.isEmpty()
                || coverage < (clientMatch ? 0.5 : 0.75))) return null;
        double score = (exactName ? 1 : coverage) * 0.8 + (clientMatch ? 0.2 : 0);
        String reason = (exactName ? "사업명 일치" : "사업명 키워드 " + hits + "/" + tokens.size() + " 일치")
                + (clientMatch ? " · 발주처 일치" : " · 발주처 확인 필요") + " · 파일 내용 확인 후 직접 선택";
        return new Match(item, score, reason);
    }

    public PerformanceDriveFileRepository.Reference selectable(String id, EvidenceType type) {
        var ref = checkedReference(id);
        return selectable(ref, type);
    }

    public PerformanceDriveFileRepository.Reference selectable(Entry entry, String id, EvidenceType type) {
        var recommended = recommended(entry);
        if (type == null || recommended.type() != type) throw new IllegalArgumentException("현재 실적에 추천된 증빙 후보를 선택하세요.");
        Match match = recommended.matches().stream().filter(candidate -> {
            String resolvedId = references.findByLocation(origin(), properties.getCompany(), candidate.item().path())
                    .map(PerformanceDriveFileRepository.Reference::id).orElseGet(() -> candidateId(candidate.item().path()));
            return resolvedId.equals(id) || candidateId(candidate.item().path()).equals(id);
        }).findFirst().orElseThrow(() -> new IllegalArgumentException("현재 실적에 추천된 증빙 후보를 선택하세요."));
        var item = verifiedItem(match.item(), type);
        return references.register(origin(), properties.getCompany(), item, id);
    }

    private PerformanceDriveFileRepository.Reference selectable(PerformanceDriveFileRepository.Reference ref, EvidenceType type) {
        var item = verifiedItem(new FmsDrivePort.Item(ref.filename(), ref.path(), false, ref.size(), ref.modified()), type);
        return references.register(origin(), properties.getCompany(), item);
    }

    private FmsDrivePort.Item verifiedItem(FmsDrivePort.Item candidate, EvidenceType type) {
        // Re-list the parent to reject stale/missing references; never accept a path from an API client.
        var item = drive.list(FmsDriveHttpAdapter.parentOf(candidate.path())).stream()
                .filter(file -> !file.directory() && file.path().equals(candidate.path())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Drive 파일을 찾을 수 없습니다. 후보를 다시 조회하세요."));
        if (evidenceType(item.name()) != type) throw new IllegalArgumentException("Drive 파일과 증빙유형이 일치하지 않습니다.");
        return item;
    }

    public InputStream open(String id) {
        var ref = checkedReference(id);
        // list.canDownload is not populated by the current FMS list implementation.
        if (!drive.canDownload(ref.path())) {
            throw new FmsDriveException("선택한 FMS 파일의 다운로드 권한이 없습니다.", true);
        }
        return drive.download(ref.path());
    }

    private PerformanceDriveFileRepository.Reference checkedReference(String id) {
        var ref = references.find(id);
        List<String> roots = new ArrayList<>(properties.getCertificateFolders());
        roots.addAll(properties.getContractFolders());
        boolean allowed = roots.stream().filter(root -> root != null && !root.isBlank())
                .map(FmsDriveHttpAdapter::normalizedPath)
                .anyMatch(root -> root.equals("/") || ref.path().startsWith(root + "/"));
        if (!allowed || !ref.origin().equals(origin()) || !ref.company().equals(properties.getCompany())) {
            throw new IllegalArgumentException("현재 FMS 검색 설정에 속한 파일을 선택하세요.");
        }
        return ref;
    }
    private String origin() { return properties.getBaseUrl().replaceAll("/+$", ""); }
    private static String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
