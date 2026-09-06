package com.audiobookfactory.control.job;

import java.time.Instant;

public record JobClaim(
        long jobId,
        long bookId,
        long bookVersionId,
        long chapterId,
        int chapterIndex,
        int segmentIndex,
        String text,
        String presetSnapshot,
        String leaseOwner,
        Instant leaseUntil,
        int attempts,
        String runId,
        String batchId,
        String scopeId) {

    public JobClaim(long jobId, long bookId, long bookVersionId, long chapterId,
                    int chapterIndex, int segmentIndex, String text, String presetSnapshot,
                    String leaseOwner, Instant leaseUntil, int attempts) {
        this(jobId, bookId, bookVersionId, chapterId, chapterIndex, segmentIndex, text,
                presetSnapshot, leaseOwner, leaseUntil, attempts, null, null, null);
    }

    public JobClaim {
        if (jobId <= 0 || bookId <= 0 || bookVersionId <= 0 || chapterId <= 0) {
            throw new IllegalArgumentException("claim ids must be positive");
        }
        if (chapterIndex <= 0 || segmentIndex <= 0) {
            throw new IllegalArgumentException("claim indexes must be positive");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("claim text must not be blank");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        if (leaseUntil == null) {
            throw new IllegalArgumentException("leaseUntil must not be null");
        }
        if (attempts <= 0) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        if (scopeId != null && scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }
}
