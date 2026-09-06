package com.audiobookfactory.control.library;

import com.audiobookfactory.control.audio.FfmpegMediaService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryPublishServiceTest {

    @Test
    void completesChapterOnlyAfterMergeAndScanSucceed() throws IOException {
        Path libraryRoot = Files.createTempDirectory("library-publish-success-");
        Path segment = Files.write(libraryRoot.resolve("segment.wav"), new byte[2048]);
        Path published = libraryRoot.resolve("测试书").resolve("001 - 第一章.mp3");
        FfmpegMediaService mediaService = mock(FfmpegMediaService.class);
        ChapterCompletionPort completionPort = mock(ChapterCompletionPort.class);
        List<String> events = new ArrayList<>();
        when(mediaService.mergeChapter(any(), any(), any())).thenAnswer(invocation -> {
            Files.createDirectories(published.getParent());
            Files.write(published, new byte[2048]);
            events.add("merge");
            return published;
        });
        LibraryPublishService.ScanInvoker scan = ignored -> events.add("scan");
        doAnswer(invocation -> {
            events.add("complete");
            return null;
        }).when(completionPort).completeChapter(anyLong(), anyString());
        LibraryPublishService publisher = new LibraryPublishService(
                mediaService, libraryRoot, "library-1", scan, completionPort);

        Path result = publisher.publishChapter(
                new LibraryPublishService.Chapter(7L, "测试书", 1, "第一章"),
                List.of(new LibraryPublishService.AudioAsset(1, segment)));

        assertThat(result).isEqualTo(published);
        assertThat(events).containsExactly("merge", "scan", "complete");
        verify(completionPort).completeChapter(7L, published.toString());
    }

    @Test
    void scanFailureDoesNotCompleteTheChapter() throws IOException {
        Path libraryRoot = Files.createTempDirectory("library-publish-scan-failure-");
        Path segment = Files.write(libraryRoot.resolve("segment.wav"), new byte[2048]);
        Path published = libraryRoot.resolve("测试书").resolve("001 - 第一章.mp3");
        FfmpegMediaService mediaService = mock(FfmpegMediaService.class);
        ChapterCompletionPort completionPort = mock(ChapterCompletionPort.class);
        when(mediaService.mergeChapter(any(), any(), any())).thenAnswer(invocation -> {
            Files.createDirectories(published.getParent());
            Files.write(published, new byte[2048]);
            return published;
        });
        LibraryPublishService.ScanInvoker scan = ignored -> {
            throw new LibraryPublishService.PublishException(
                    "AUDIOBOOKSHELF_SCAN_FAILED", "scan failed");
        };
        LibraryPublishService publisher = new LibraryPublishService(
                mediaService, libraryRoot, "library-1", scan, completionPort);

        assertThatThrownBy(() -> publisher.publishChapter(
                new LibraryPublishService.Chapter(7L, "测试书", 1, "第一章"),
                java.util.List.of(new LibraryPublishService.AudioAsset(1, segment))))
                .isInstanceOf(LibraryPublishService.PublishException.class)
                .extracting("code")
                .isEqualTo("AUDIOBOOKSHELF_SCAN_FAILED");

        verify(completionPort, never()).completeChapter(anyLong(), anyString());
    }

    @Test
    void disabledAudiobookshelfStillPublishesAudioForDownload() throws IOException {
        Path libraryRoot = Files.createTempDirectory("library-publish-download-");
        Path segment = Files.write(libraryRoot.resolve("segment.wav"), new byte[2048]);
        Path published = libraryRoot.resolve("book").resolve("001 - chapter.mp3");
        FfmpegMediaService mediaService = mock(FfmpegMediaService.class);
        ChapterCompletionPort completionPort = mock(ChapterCompletionPort.class);
        List<String> events = new ArrayList<>();
        when(mediaService.mergeChapter(any(), any(), any())).thenAnswer(invocation -> {
            Files.createDirectories(published.getParent());
            Files.write(published, new byte[2048]);
            events.add("merge");
            return published;
        });
        LibraryPublishService.ScanInvoker scan = ignored -> events.add("scan");
        LibraryPublishService publisher = new LibraryPublishService(
                mediaService, libraryRoot, libraryRoot, scan, "library-1", completionPort, false);

        Path result = publisher.publishChapter(
                new LibraryPublishService.Chapter(8L, "book", 1, "chapter"),
                List.of(new LibraryPublishService.AudioAsset(1, segment)));

        assertThat(result).isEqualTo(published);
        assertThat(events).containsExactly("merge");
        verify(completionPort).completeChapter(8L, published.toString());
        verify(completionPort).completeChapter(anyLong(), anyString());
    }
}
