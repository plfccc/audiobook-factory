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
            "WORKER_STOPPED", "WORKER_STOPPING", "STOPPED");

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
        String key = normalizeKey(normalized);
        if (normalized.chars().anyMatch(Character::isISOControl)
                || key.contains("token") || key.contains("secret") || key.contains("password")
                || key.contains("prompt") || key.contains("traceback") || key.contains("stacktrace")
                || key.contains("exception") || key.contains("authorization")
                || key.contains("cookie") || key.contains("apikey") || key.contains("privatekey")) {
            return GENERIC_SUMMARY;
        }
        return normalized.length() > MAX_SUMMARY_LENGTH
                ? normalized.substring(0, MAX_SUMMARY_LENGTH) : normalized;
    }

    private static String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
