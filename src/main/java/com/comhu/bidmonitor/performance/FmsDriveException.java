package com.comhu.bidmonitor.performance;

/** Never retain remote response bodies, credentials or paths in outward exceptions. */
public class FmsDriveException extends RuntimeException {
    enum Stage { CONFIG, LIST, PERMISSION, DOWNLOAD, VALIDATION, INDEX_WRITE, REFRESH }
    enum Kind { HTTP_ERROR, CONNECTION_REFUSED, TIMEOUT, INVALID_ROOT, INVALID_CONFIG, INVALID_RESPONSE, IO_ERROR, INTERRUPTED, UNKNOWN }
    private final boolean forbidden;
    private Stage stage = Stage.REFRESH;
    private Kind kind = Kind.UNKNOWN;
    private Integer httpStatus;
    private Throwable diagnostic;
    public FmsDriveException(String message) { this(message, false); }
    public FmsDriveException(String message, boolean forbidden) { super(message); this.forbidden = forbidden; }
    public boolean forbidden() { return forbidden; }
    FmsDriveException details(Stage stage, Kind kind, Integer status, Throwable original) {
        this.stage = stage; this.kind = kind; this.httpStatus = status;
        this.diagnostic = original == null ? null : sanitized(original);
        return this;
    }
    Stage stage() { return stage; }
    Kind kind() { return kind; }
    Integer httpStatus() { return httpStatus; }
    Throwable diagnostic() { return diagnostic == null ? sanitized(this) : diagnostic; }
    /** Keep original exception types and call sites, never messages, SQL, URLs or response bodies. */
    static Throwable sanitized(Throwable original) {
        return sanitized(original, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }
    private static Throwable sanitized(Throwable original, java.util.Set<Throwable> seen, int depth) {
        if (original == null || depth >= 8 || !seen.add(original)) return null;
        var safe = new RuntimeException(original.getClass().getName(), sanitized(original.getCause(), seen, depth + 1));
        safe.setStackTrace(original.getStackTrace());
        return safe;
    }
}
