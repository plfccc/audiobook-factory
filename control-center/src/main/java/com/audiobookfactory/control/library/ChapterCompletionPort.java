package com.audiobookfactory.control.library;

/** 发布完成后推进章节状态的边界，避免媒体发布器直接依赖 JobService。 */
@FunctionalInterface
public interface ChapterCompletionPort {

    void completeChapter(long chapterId, String finalAudioPath);
}
