package gen5w.svm.diagnostics;

final class SessionPolicy {
    static final long INTERVAL_MS = 2000;
    static final long DURATION_MS = 15 * 60 * 1000;
    static final long MAX_BYTES = 50L * 1000 * 1000;
    static final long MIN_FREE_BYTES = 8L * 1024 * 1024;
    static final long FINALIZATION_BYTES = 512L * 1024;

    static String stopReason(boolean cancelled, long elapsed, long used, long free) {
        if (cancelled) return "stopped_by_user";
        if (elapsed >= DURATION_MS) return "duration_limit";
        if (used >= MAX_BYTES - FINALIZATION_BYTES) return "session_storage_limit";
        if (free < MIN_FREE_BYTES) return "low_available_storage";
        return null;
    }

    static boolean permitsAppend(long used, int appendBytes) {
        return appendBytes <= MAX_BYTES - FINALIZATION_BYTES - used;
    }

    private SessionPolicy() {}
}
