package com.audiobookfactory.control.library;

import com.audiobookfactory.control.audio.FfmpegMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class LibraryPublishService {

    private static final String ENV_LIBRARY_ROOT = "AUDIOBOOKSHELF_LIBRARY_ROOT";
    private static final String ENV_LIBRARY_PATH = "AUDIOBOOKSHELF_LIBRARY_PATH";
    private static final String ENV_LIBRARY_ID = "AUDIOBOOKSHELF_LIBRARY_ID";
    private static final String ENV_ENABLED = "AUDIOBOOKSHELF_ENABLED";
    private static final ChapterCompletionPort NOOP_COMPLETION = (chapterId, finalAudioPath) -> { };

    private final FfmpegMediaService mediaService;
    private final Path libraryRoot;
    private final Path sourceRoot;
    private final ScanInvoker scanInvoker;
    private final String libraryId;
    private final ChapterCompletionPort completionPort;
    private final boolean scanEnabled;

    @Autowired
    public LibraryPublishService(FfmpegMediaService mediaService, AudiobookshelfClient client,
                                 @Lazy ChapterCompletionPort completionPort) {
        this(mediaService, libraryRootFromEnvironment(), sourceRootFromEnvironment(),
                client::scan, System.getenv(ENV_LIBRARY_ID), completionPort, enabledFromEnvironment());
    }

    public LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot,
                                 ScanInvoker scanInvoker) {
        this(mediaService, libraryRoot, null, scanInvoker, null, NOOP_COMPLETION);
    }

    public LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot,
                                 AudiobookshelfClient client) {
        this(mediaService, libraryRoot, null, client::scan, client.configuredLibraryId(),
                NOOP_COMPLETION);
    }

    public LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot,
                                 String libraryId, ScanInvoker scanInvoker) {
        this(mediaService, libraryRoot, null, scanInvoker, libraryId, NOOP_COMPLETION);
    }

    public LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot,
                                 String libraryId, ScanInvoker scanInvoker,
                                 ChapterCompletionPort completionPort) {
        this(mediaService, libraryRoot, null, scanInvoker, libraryId, completionPort);
    }

    public LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot,
                                 ScanInvoker scanInvoker, ChapterCompletionPort completionPort) {
        this(mediaService, libraryRoot, null, scanInvoker, null, completionPort);
    }

    LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot, Path sourceRoot,
                          ScanInvoker scanInvoker, String libraryId) {
        this(mediaService, libraryRoot, sourceRoot, scanInvoker, libraryId, NOOP_COMPLETION);
    }

    LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot, Path sourceRoot,
                          ScanInvoker scanInvoker, String libraryId,
                          ChapterCompletionPort completionPort) {
        this(mediaService, libraryRoot, sourceRoot, scanInvoker, libraryId, completionPort, true);
    }

    LibraryPublishService(FfmpegMediaService mediaService, Path libraryRoot, Path sourceRoot,
                          ScanInvoker scanInvoker, String libraryId,
                          ChapterCompletionPort completionPort, boolean scanEnabled) {
        this.mediaService = Objects.requireNonNull(mediaService, "mediaService must not be null");
        this.libraryRoot = normalizeRoot(libraryRoot, "libraryRoot");
        this.sourceRoot = sourceRoot == null ? null : normalizeRoot(sourceRoot, "sourceRoot");
        this.scanInvoker = Objects.requireNonNull(scanInvoker, "scanInvoker must not be null");
        this.libraryId = blankToNull(libraryId);
        this.completionPort = Objects.requireNonNull(completionPort,
                "completionPort must not be null");
        this.scanEnabled = scanEnabled;
    }

    public Path publishChapter(Chapter chapter, List<AudioAsset> assets) {
        Objects.requireNonNull(chapter, "chapter must not be null");
        List<AudioAsset> orderedAssets = orderAssets(assets);
        Path chapterDirectory = chapterDirectory(chapter);
        Path output = chapterDirectory.resolve(chapterFilename(chapter)).normalize();
        ensureWithinLibrary(output);
        ensureNoSymbolicLinkComponents(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(output)
                || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS))) {
            throw new PublishException("PUBLISH_PATH_INVALID", "Chapter output is not a regular file");
        }

        List<Path> paths = new ArrayList<>(orderedAssets.size());
        for (AudioAsset asset : orderedAssets) {
            Path path = safeAssetPath(asset.path());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new PublishException("AUDIO_INVALID", "Chapter audio asset is not a regular file");
            }
            paths.add(path);
        }

        Path merged = mediaService.mergeChapter(paths, output,
                new FfmpegMediaService.ChapterMetadata(
                        chapter.chapterNumber(), chapter.title(), chapter.bookTitle()));
        if (!output.equals(merged) || Files.isSymbolicLink(merged)
                || !Files.isRegularFile(merged, LinkOption.NOFOLLOW_LINKS)) {
            throw new PublishException("PUBLISH_FAILED", "Chapter output was not published");
        }
        if (scanEnabled) {
            try {
                scanInvoker.scan(libraryId);
            } catch (FfmpegMediaService.MediaPipelineException | PublishException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new PublishException("AUDIOBOOKSHELF_SCAN_FAILED",
                        "Audiobookshelf scan failed", exception);
            }
        }
        try {
            completionPort.completeChapter(chapter.chapterId(), merged.toString());
        } catch (PublishException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PublishException("CHAPTER_COMPLETION_FAILED",
                    "Chapter completion failed", exception);
        }
        return merged;
    }

    private List<AudioAsset> orderAssets(List<AudioAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            throw new PublishException("AUDIO_INVALID", "Chapter has no audio assets");
        }
        List<AudioAsset> ordered = new ArrayList<>(assets);
        for (AudioAsset asset : ordered) {
            if (asset == null) {
                throw new PublishException("AUDIO_INVALID", "Chapter audio asset is required");
            }
        }
        ordered.sort(Comparator.comparingInt(AudioAsset::segmentIndex));
        for (int index = 0; index < ordered.size(); index++) {
            AudioAsset asset = ordered.get(index);
            if (asset == null || asset.segmentIndex() != index + 1) {
                throw new PublishException("AUDIO_INVALID",
                        "Chapter audio segments are missing or duplicated");
            }
        }
        return List.copyOf(ordered);
    }

    private Path chapterDirectory(Chapter chapter) {
        try {
            Files.createDirectories(libraryRoot);
            if (Files.isSymbolicLink(libraryRoot)
                    || !Files.isDirectory(libraryRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new PublishException("PUBLISH_PATH_INVALID", "Audiobookshelf library root is invalid");
            }
            Path bookDirectory = libraryRoot.resolve(safeName(chapter.bookTitle(), "book")).normalize();
            ensureWithinLibrary(bookDirectory);
            ensureNoSymbolicLinkComponents(bookDirectory);
            Files.createDirectories(bookDirectory);
            if (Files.isSymbolicLink(bookDirectory)
                    || !Files.isDirectory(bookDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new PublishException("PUBLISH_PATH_INVALID", "Book library directory is invalid");
            }
            return bookDirectory;
        } catch (PublishException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new PublishException("PUBLISH_PATH_INVALID",
                    "Unable to prepare Audiobookshelf library directory", exception);
        }
    }

    private Path safeAssetPath(Path assetPath) {
        if (assetPath == null) {
            throw new PublishException("AUDIO_INVALID", "Audio asset path is missing");
        }
        Path normalized = assetPath.toAbsolutePath().normalize();
        if (sourceRoot != null && !normalized.startsWith(sourceRoot)) {
            throw new PublishException("AUDIO_INVALID", "Audio asset path is outside storage root");
        }
        ensureNoSymbolicLinkComponents(normalized);
        return normalized;
    }

    private String chapterFilename(Chapter chapter) {
        return String.format(Locale.ROOT, "%03d - %s.mp3",
                chapter.chapterNumber(), safeName(chapter.title(), "chapter"));
    }

    private String safeName(String value, String fallback) {
        StringBuilder clean = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            boolean safe = Character.isLetterOrDigit(codePoint)
                    || codePoint == ' ' || codePoint == '_' || codePoint == '-'
                    || codePoint == '.';
            clean.appendCodePoint(safe ? codePoint : '_');
        });
        String result = clean.toString().replaceAll("\\s+", " ").trim();
        while (result.contains("..")) {
            result = result.replace("..", "_");
        }
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank() || ".".equals(result) || "..".equals(result)) {
            result = fallback;
        }
        if (isWindowsReservedName(result)) {
            result = "_" + result;
        }
        return result.length() > 120 ? result.substring(0, 120).trim() : result;
    }

    private boolean isWindowsReservedName(String value) {
        String stem = value;
        int extension = stem.indexOf('.');
        if (extension >= 0) {
            stem = stem.substring(0, extension);
        }
        String normalized = stem.toUpperCase(Locale.ROOT);
        return normalized.equals("CON") || normalized.equals("PRN")
                || normalized.equals("AUX") || normalized.equals("NUL")
                || normalized.matches("COM[1-9]") || normalized.matches("LPT[1-9]");
    }

    private void ensureWithinLibrary(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(libraryRoot) || normalized.equals(libraryRoot)) {
            throw new PublishException("PUBLISH_PATH_INVALID", "Library path escapes configured root");
        }
    }

    private void ensureNoSymbolicLinkComponents(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        if (current == null) {
            throw new PublishException("PUBLISH_PATH_INVALID", "Library path is invalid");
        }
        for (Path component : normalized) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new PublishException("PUBLISH_PATH_INVALID", "Library path contains a symbolic link");
            }
        }
    }

    private Path normalizeRoot(Path root, String name) {
        if (root == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return root.toAbsolutePath().normalize();
    }

    private static Path libraryRootFromEnvironment() {
        String configured = firstNonBlank(System.getenv(ENV_LIBRARY_ROOT), System.getenv(ENV_LIBRARY_PATH));
        return Path.of(configured == null ? "./library" : configured);
    }

    private static Path sourceRootFromEnvironment() {
        String configured = System.getenv("STORAGE_ROOT");
        return Path.of(configured == null || configured.isBlank() ? "./data" : configured);
    }

    private static String firstNonBlank(String first, String second) {
        return blankToNull(first) != null ? first : blankToNull(second);
    }

    private static boolean enabledFromEnvironment() {
        String configured = System.getenv(ENV_ENABLED);
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @FunctionalInterface
    public interface ScanInvoker {
        void scan(String libraryId);
    }

    public record Chapter(long chapterId, String bookTitle, int chapterNumber, String title) {

        public Chapter {
            if (bookTitle == null || bookTitle.isBlank()) {
                throw new IllegalArgumentException("bookTitle must not be blank");
            }
            if (chapterNumber <= 0) {
                throw new IllegalArgumentException("chapterNumber must be positive");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
        }

        public Chapter(String bookTitle, int chapterNumber, String title) {
            this(0, bookTitle, chapterNumber, title);
        }

        public Chapter(int chapterNumber, String title, String bookTitle) {
            this(0, bookTitle, chapterNumber, title);
        }

        public Chapter(long chapterId, int chapterNumber, String title, String bookTitle) {
            this(chapterId, bookTitle, chapterNumber, title);
        }
    }

    public record AudioAsset(int segmentIndex, Path path) {

        public AudioAsset {
            if (segmentIndex <= 0) {
                throw new IllegalArgumentException("segmentIndex must be positive");
            }
            Objects.requireNonNull(path, "path must not be null");
        }

        public AudioAsset(Path path, int segmentIndex) {
            this(segmentIndex, path);
        }

        public AudioAsset(int segmentIndex, String path) {
            this(segmentIndex, Path.of(path));
        }

        public Path filePath() {
            return path;
        }
    }

    public static final class PublishException extends RuntimeException {
        private final String code;

        public PublishException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public PublishException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public String code() {
            return code;
        }
    }
}
