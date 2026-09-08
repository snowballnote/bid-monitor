package com.comhu.bidmonitor.performance;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface FmsDrivePort {
    /** Internal-only metadata. Never serialize this record through our controllers. */
    record Item(String name, String path, boolean directory, long size, Instant lastModified) { }
    List<Item> list(String folder);
    boolean canDownload(String path);
    InputStream download(String path);
}