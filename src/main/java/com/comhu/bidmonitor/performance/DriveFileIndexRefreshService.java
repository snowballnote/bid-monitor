package com.comhu.bidmonitor.performance;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DriveFileIndexRefreshService {
    public record RootStatus(String label, DriveFileIndexRepository.State state) { }
    private record Folder(String path, int depth) { }
    private final FmsDrivePort drive;
    private final FmsDriveProperties properties;
    private final DriveFileIndexRepository index;
    private final AtomicBoolean running = new AtomicBoolean();
    public DriveFileIndexRefreshService(FmsDrivePort drive, FmsDriveProperties properties, DriveFileIndexRepository index) {
        this.drive = drive; this.properties = properties; this.index = index;
    }
    private String source() { return properties.getBaseUrl().replaceAll("/+$", ""); }
    private List<String> roots() {
        List<String> roots = new ArrayList<>(properties.getCertificateFolders());
        roots.addAll(properties.getContractFolders());
        return roots.stream().filter(root -> root != null && !root.isBlank())
                .map(FmsDriveHttpAdapter::normalizedPath).distinct().toList();
    }
    public List<RootStatus> status() {
        List<RootStatus> result = new ArrayList<>();
        var roots = roots();
        for (int i = 0; i < roots.size(); i++)
            result.add(new RootStatus("검색 범위 " + (i + 1), index.state(source(), properties.getCompany(), roots.get(i))));
        return result;
    }
    // No transaction surrounds FMS calls. Only repository snapshot replacement is transactional.
    public List<RootStatus> refresh() {
        if (!running.compareAndSet(false, true)) throw new FmsDriveException("Drive 인덱스를 이미 갱신하고 있습니다.");
        try {
            var roots = roots();
            if (roots.isEmpty()) throw new FmsDriveException("Drive 검색 폴더를 설정하세요.");
            String source = source();
            String company = properties.getCompany();
            for (String root : roots) {
                index.started(source, company, root);
                try {
                    ArrayDeque<Folder> queue = new ArrayDeque<>();
                    queue.add(new Folder(root, 0));
                    Set<String> visited = new HashSet<>();
                    Map<String, FmsDrivePort.Item> files = new LinkedHashMap<>();
                    while (!queue.isEmpty()) {
                        Folder folder = queue.removeFirst();
                        if (!visited.add(folder.path())) continue;
                        if (visited.size() > Math.max(1, properties.getMaxFolders())) throw new FmsDriveException("폴더 수 제한 초과");
                        for (var item : drive.list(folder.path())) {
                            if (item.name().startsWith(".") || item.name().startsWith("~$")) continue;
                            String path = FmsDriveHttpAdapter.normalizedPath(item.path());
                            if (!FmsDriveHttpAdapter.parentOf(path).equals(folder.path())) throw new FmsDriveException("폴더 범위 오류");
                            if (item.directory()) {
                                if (folder.depth() >= Math.max(0, properties.getMaxDepth())) throw new FmsDriveException("검색 깊이 초과");
                                queue.add(new Folder(path, folder.depth() + 1));
                            } else {
                                files.putIfAbsent(path, item);
                                if (files.size() > Math.max(1, properties.getMaxFiles())) throw new FmsDriveException("파일 수 제한 초과");
                            }
                        }
                    }
                    index.replace(source, company, root, new ArrayList<>(files.values()), visited.size());
                } catch (RuntimeException failure) {
                    // Never store remote exception messages, paths or credentials in public status.
                    index.failed(source, company, root);
                }
            }
            return status();
        } finally { running.set(false); }
    }
}
