package com.comhu.bidmonitor.performance;

import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class DriveEvidenceService {
    private final FmsDrivePort drive;
    private final FmsDriveProperties properties;
    private final PerformanceDriveFileRepository references;
    public DriveEvidenceService(FmsDrivePort drive, FmsDriveProperties properties, PerformanceDriveFileRepository references) {
        this.drive = drive; this.properties = properties; this.references = references;
    }
    private record Folder(String path, int depth) { }
    private record Match(FmsDrivePort.Item item, double score, String reason) { }

    public Recommendations recommend(Entry entry) {
        if (entry.resolvedStatus() == BusinessStatus.COMPLETED) {
            var certificates = search(entry, EvidenceType.CERTIFICATE, properties.getCertificateFolders());
            if (!certificates.isEmpty()) return new Recommendations(certificates, "실적증명서 후보 확인 후 직접 선택");
        }
        var contracts = search(entry, EvidenceType.CONTRACT, properties.getContractFolders());
        return new Recommendations(contracts, contracts.isEmpty() ? "KITC 요청 필요" : "계약서 후보 확인 후 직접 선택");
    }

    private List<Candidate> search(Entry entry, EvidenceType type, List<String> roots) {
        if (roots == null || roots.isEmpty() || roots.stream().allMatch(String::isBlank)) {
            throw new FmsDriveException(type.label + " 검색 폴더를 설정하세요.");
        }
        ArrayDeque<Folder> queue = new ArrayDeque<>();
        roots.stream().filter(root -> !root.isBlank()).map(FmsDriveHttpAdapter::normalizedPath)
                .distinct().forEach(root -> queue.add(new Folder(root, 0)));
        Set<String> visited = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        List<Match> matches = new ArrayList<>();
        while (!queue.isEmpty()) {
            Folder folder = queue.removeFirst();
            if (!visited.add(folder.path())) continue;
            if (visited.size() > Math.max(1, properties.getMaxFolders())) {
                throw new FmsDriveException("검색 폴더 수 제한을 초과했습니다. 검색 범위를 좁혀주세요.");
            }
            for (var item : drive.list(folder.path())) {
                String path = FmsDriveHttpAdapter.normalizedPath(item.path());
                if (!FmsDriveHttpAdapter.parentOf(path).equals(folder.path())) {
                    throw new FmsDriveException("Drive 목록이 검색 폴더 범위를 벗어났습니다.");
                }
                if (item.directory()) {
                    if (folder.depth() >= Math.max(0, properties.getMaxDepth())) {
                        throw new FmsDriveException("하위 폴더 검색 깊이를 초과했습니다. 대상 폴더를 직접 설정하세요.");
                    }
                    queue.add(new Folder(path, folder.depth() + 1));
                    continue;
                }
                if (!seenFiles.add(path)) continue;
                if (seenFiles.size() > Math.max(1, properties.getMaxFiles())) {
                    throw new FmsDriveException("검색 파일 수 제한을 초과했습니다. 검색 범위를 좁혀주세요.");
                }
                if (evidenceType(item.name()) != type) continue;
                Match match = match(entry.info(), item);
                if (match != null) matches.add(match);
            }
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(match -> match.item().lastModified(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(match -> match.item().path()))
                .limit(30).map(match -> {
                    var ref = references.register(origin(), properties.getCompany(), match.item());
                    return new Candidate(new EvidenceFile(null, ref.id(), ref.filename(), ref.ext(), ref.size(), ref.modified()),
                            type, match.reason());
                }).toList();
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
        // Re-list the parent to reject stale/missing references; never accept a path from an API client.
        var item = drive.list(FmsDriveHttpAdapter.parentOf(ref.path())).stream()
                .filter(file -> !file.directory() && file.path().equals(ref.path())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Drive 파일을 찾을 수 없습니다. 후보를 다시 조회하세요."));
        if (evidenceType(item.name()) != type) throw new IllegalArgumentException("Drive 파일과 증빙유형이 일치하지 않습니다.");
        return references.register(origin(), properties.getCompany(), item);
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