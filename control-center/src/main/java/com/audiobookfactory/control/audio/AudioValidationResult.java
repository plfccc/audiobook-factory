package com.audiobookfactory.control.audio;

import java.util.Locale;

public record AudioValidationResult(
        boolean valid,
        String errorCode,
        String errorMessage,
        long sizeBytes,
        double durationSeconds,
        String codec,
        int sampleRate,
        int channels,
        String sha256) {

    public AudioValidationResult {
        if (errorCode != null) {
            errorCode = errorCode.trim().toUpperCase(Locale.ROOT);
        }
        if (sha256 != null) {
            sha256 = sha256.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static AudioValidationResult valid(long sizeBytes, double durationSeconds,
                                              String codec, int sampleRate, int channels,
                                              String sha256) {
        return new AudioValidationResult(true, null, null, sizeBytes, durationSeconds,
                codec, sampleRate, channels, sha256);
    }

    public static AudioValidationResult invalid(String errorCode, String errorMessage) {
        return new AudioValidationResult(false, errorCode, errorMessage,
                -1, -1, null, -1, -1, null);
    }

    public boolean isValid() {
        return valid;
    }

    public String error() {
        return errorMessage;
    }

    public long durationMs() {
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0) {
            return -1;
        }
        return Math.round(durationSeconds * 1000.0);
    }
}
