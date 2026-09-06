package com.audiobookfactory.control.job;

import java.util.Locale;
import java.util.Set;

/** Keeps worker failure data safe and bounded at persistence and API boundaries. */
public final class FailureSanitizer {

    public static final String GENERIC_SUMMARY = "Worker reported a failure";
    public static final int MAX_SUMMARY_LENGTH = 240;

    private static final Set<String> ALLOWED_CODES = Set.of(
            "AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED", "WAITING_FOR_GPU",
            "WORKER_UNAUTHORIZED", "PAGE_NOT_READY", "GENERATION_TIMEOUT",
            "DOWNLOAD_TIMEOUT", "AUDIO_INVALID", "PERMANENT_FAILED", "TEMPORARY_FAILURE",
            "MODEL_NOT_COMPATIBLE", "INVALID_JOB_ID", "INVALID_ASSET_SHA256",
            "INVALID_RESULT_SHA256", "OUTPUT_PATH_INVALID", "WORKER_DISABLED", "REVOKED",
            "WORKER_STOPPED", "WORKER_STOPPING", "STOPPED", "UNKNOWN_FAILURE");
    private static final Set<String> SAFE_SUMMARIES = Set.of(
            "audio invalid", "bad audio", "result audio is invalid", "sign in required",
            "quota paused", "waiting for gpu", "worker authorization is required",
            "worker lease is no longer valid", "page not ready", "generation timeout",
            "download timeout", "temporary failure", "model not compatible",
            "output path invalid", "asset not found", GENERIC_SUMMARY.toLowerCase(Locale.ROOT));

    private FailureSanitizer() {
    }

    public static boolean isAllowedCode(String code) {
        return code != null
                && code.matches("[A-Za-z0-9_.-]{1,128}")
                && ALLOWED_CODES.contains(code.toUpperCase(Locale.ROOT));
    }

    public static String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return isAllowedCode(normalized) ? normalized : "UNKNOWN_FAILURE";
    }

    public static String sanitizeSummary(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_SUMMARY_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return GENERIC_SUMMARY;
        }
        return SAFE_SUMMARIES.contains(normalized.toLowerCase(Locale.ROOT))
                ? normalized : GENERIC_SUMMARY;
    }
}
