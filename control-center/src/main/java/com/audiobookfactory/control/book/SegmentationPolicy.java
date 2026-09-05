package com.audiobookfactory.control.book;

public record SegmentationPolicy(
        int minChars,
        int targetChars,
        int maxChars,
        String rulesVersion) {

    public SegmentationPolicy {
        if (minChars <= 0) {
            throw new IllegalArgumentException("minChars must be positive");
        }
        if (targetChars < minChars) {
            throw new IllegalArgumentException("targetChars must not be smaller than minChars");
        }
        if (maxChars < targetChars) {
            throw new IllegalArgumentException("maxChars must not be smaller than targetChars");
        }
        if (rulesVersion == null || rulesVersion.isBlank()) {
            throw new IllegalArgumentException("rulesVersion must not be blank");
        }
        rulesVersion = rulesVersion.trim();
    }

    public static SegmentationPolicy notebookDefaults() {
        return new SegmentationPolicy(90, 220, 320, "v1-notebook");
    }
}
