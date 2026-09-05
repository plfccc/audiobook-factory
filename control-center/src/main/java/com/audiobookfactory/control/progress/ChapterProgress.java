package com.audiobookfactory.control.progress;

public record ChapterProgress(
        int completedChapters,
        int totalChapters,
        Integer currentChapter) {

    public ChapterProgress {
        if (completedChapters < 0 || totalChapters < 0) {
            throw new IllegalArgumentException("chapter counts must not be negative");
        }
        if (completedChapters > totalChapters) {
            throw new IllegalArgumentException("completedChapters must not exceed totalChapters");
        }
        if (currentChapter != null && currentChapter <= 0) {
            throw new IllegalArgumentException("currentChapter must be positive when present");
        }
    }
}
