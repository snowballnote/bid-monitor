package com.comhu.bidmonitor.performance;

import java.io.IOException;
import java.io.InputStream;

/** Read-only file body access. Physical locations never leave the adapter. */
public interface PerformanceFileContentPort {
    InputStream open(long fileId) throws IOException;
}