package com.comhu.bidmonitor.performance;

/** Never retain remote response bodies, credentials or paths in outward exceptions. */
public class FmsDriveException extends RuntimeException {
    private final boolean forbidden;
    public FmsDriveException(String message) { this(message, false); }
    public FmsDriveException(String message, boolean forbidden) { super(message); this.forbidden = forbidden; }
    public boolean forbidden() { return forbidden; }
}