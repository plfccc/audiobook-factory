package com.audiobookfactory.control.audio;

import com.audiobookfactory.control.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class FfmpegMediaService {

    public static final String AUDIO_INVALID = "AUDIO_INVALID";
    public static final String MEDIA_TOOL_TIMEOUT = "MEDIA_TOOL_TIMEOUT";
    public static final String MEDIA_TOOL_UNAVAILABLE = "MEDIA_TOOL_UNAVAILABLE";
    public static final String MEDIA_MERGE_FAILED = "MEDIA_MERGE_FAILED";
    public static final String MEDIA_PROMOTION_UNSUPPORTED = "MEDIA_PROMOTION_UNSUPPORTED";
    public static final long DEFAULT_MINIMUM_AUDIO_BYTES = 1024;
    public static final double DEFAULT_MINIMUM_DURATION_SECONDS = 0.01;
    public static final int DEFAULT_SAMPLE_RATE = 24_000;
    public static final int DEFAULT_CHANNELS = 1;

    private static final int MIN_SAMPLE_RATE = 8_000;
    private static final int MAX_SAMPLE_RATE = 96_000;
    private static final int MAX_CHANNELS = 2;

    private final MediaToolRunner toolRunner;
    private final ObjectMapper objectMapper;
    private final String ffprobeExecutable;
    private final String ffmpegExecutable;
    private final Duration timeout;
    private final long minimumAudioBytes;
    private final double minimumDurationSeconds;
    private final Path storageRoot;
    private final Path libraryRoot;
    private final AtomicFileMover fileMover;

    @Autowired
    public FfmpegMediaService(AppProperties appProperties,
                               @Value("${audiobookshelf.library-root}") Path libraryRoot) {
        this(new MediaToolRunner(), Objects.requireNonNull(appProperties,
                        "appProperties must not be null").storageRoot(), libraryRoot);
    }

    public FfmpegMediaService(MediaToolRunner toolRunner) {
        this(toolRunner, new ObjectMapper(), "ffprobe", "ffmpeg", MediaToolRunner.DEFAULT_TIMEOUT,
                DEFAULT_MINIMUM_AUDIO_BYTES, DEFAULT_MINIMUM_DURATION_SECONDS,
                null, null, FfmpegMediaService::moveAtomically);
    }

    public FfmpegMediaService(MediaToolRunner toolRunner, ObjectMapper objectMapper,
                              String ffprobeExecutable, String ffmpegExecutable,
                              Duration timeout, long minimumAudioBytes,
                              double minimumDurationSeconds) {
        this(toolRunner, objectMapper, ffprobeExecutable, ffmpegExecutable, timeout,
                minimumAudioBytes, minimumDurationSeconds, null, null,
                FfmpegMediaService::moveAtomically);
    }

    public FfmpegMediaService(MediaToolRunner toolRunner, Path storageRoot, Path libraryRoot) {
        this(toolRunner, new ObjectMapper(), "ffprobe", "ffmpeg", MediaToolRunner.DEFAULT_TIMEOUT,
                DEFAULT_MINIMUM_AUDIO_BYTES, DEFAULT_MINIMUM_DURATION_SECONDS,
                storageRoot, libraryRoot, FfmpegMediaService::moveAtomically);
    }

    public FfmpegMediaService(MediaToolRunner toolRunner, Path storageRoot, Path libraryRoot,
                              AtomicFileMover fileMover) {
        this(toolRunner, new ObjectMapper(), "ffprobe", "ffmpeg", MediaToolRunner.DEFAULT_TIMEOUT,
                DEFAULT_MINIMUM_AUDIO_BYTES, DEFAULT_MINIMUM_DURATION_SECONDS,
                storageRoot, libraryRoot, fileMover);
    }

    private FfmpegMediaService(MediaToolRunner toolRunner, ObjectMapper objectMapper,
                               String ffprobeExecutable, String ffmpegExecutable,
                               Duration timeout, long minimumAudioBytes,
                               double minimumDurationSeconds, Path storageRoot,
                               Path libraryRoot, AtomicFileMover fileMover) {
        this.toolRunner = Objects.requireNonNull(toolRunner, "toolRunner must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.ffprobeExecutable = executable(ffprobeExecutable, "ffprobeExecutable");
        this.ffmpegExecutable = executable(ffmpegExecutable, "ffmpegExecutable");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (minimumAudioBytes <= 0) {
            throw new IllegalArgumentException("minimumAudioBytes must be positive");
        }
        if (!Double.isFinite(minimumDurationSeconds) || minimumDurationSeconds <= 0) {
            throw new IllegalArgumentException("minimumDurationSeconds must be positive");
        }
        this.minimumAudioBytes = minimumAudioBytes;
        this.minimumDurationSeconds = minimumDurationSeconds;
        this.storageRoot = normalizeRoot(storageRoot);
        this.libraryRoot = normalizeRoot(libraryRoot);
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover must not be null");
    }

    public AudioValidationResult validate(Path wav) {
        if (wav == null) {
            return invalid(AUDIO_INVALID, "Audio file is missing");
        }
        try {
            Path path = normalize(wav);
            if (!within(path, storageRoot)
                    || containsSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(AUDIO_INVALID, "Audio file is missing or not a regular file");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long sizeBytes = attributes.size();
            if (sizeBytes < minimumAudioBytes) {
                return invalid(AUDIO_INVALID, "Audio file is smaller than the minimum size");
            }

            MediaToolRunner.CommandResult probe = run(probeArguments(path));
            if (probe.timedOut()) {
                return invalid(MEDIA_TOOL_TIMEOUT, "ffprobe timed out");
            }
            if (!probe.succeeded()) {
                return invalid(AUDIO_INVALID, "ffprobe rejected the audio file");
            }
            ProbeMetadata metadata = parseProbe(probe.stdout());
            if (!supportedAudio(metadata)) {
                return invalid(AUDIO_INVALID, "ffprobe returned unsupported audio metadata");
            }
            if (metadata.durationSeconds() < minimumDurationSeconds) {
                return invalid(AUDIO_INVALID, "Audio duration is below the minimum");
            }

            MediaToolRunner.CommandResult decode = run(decodeArguments(path));
            if (decode.timedOut()) {
                return invalid(MEDIA_TOOL_TIMEOUT, "ffmpeg decode timed out");
            }
            if (!decode.succeeded()) {
                return invalid(AUDIO_INVALID, "ffmpeg could not fully decode the audio");
            }

            String sha256 = sha256(path);
            if (containsSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(AUDIO_INVALID, "Audio file changed while it was validated");
            }
            long finalSize = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
            if (finalSize != sizeBytes) {
                return invalid(AUDIO_INVALID, "Audio file changed while it was validated");
            }
            return AudioValidationResult.valid(finalSize, metadata.durationSeconds(), metadata.codec(),
                    metadata.sampleRate(), metadata.channels(), sha256);
        } catch (ToolUnavailableException exception) {
            return invalid(MEDIA_TOOL_UNAVAILABLE, exception.getMessage());
        } catch (InvalidProbeException exception) {
            return invalid(AUDIO_INVALID, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            return invalid(AUDIO_INVALID, "Audio file could not be validated");
        }
    }

    public Path mergeChapter(List<Path> orderedWavFiles, Path outputMp3, ChapterMetadata metadata) {
        if (orderedWavFiles == null || orderedWavFiles.isEmpty()) {
            throw new MediaPipelineException(AUDIO_INVALID, "Chapter has no audio segments");
        }
        if (orderedWavFiles.size() > 10_000) {
            throw new MediaPipelineException(AUDIO_INVALID, "Chapter has too many audio segments");
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        Path output = normalizeOutput(outputMp3);
        if (!within(output, libraryRoot)) {
            throw new MediaPipelineException("OUTPUT_PATH_INVALID",
                    "Output path is outside the configured library root");
        }
        ensureOutputCanBeWritten(output);

        List<Path> inputs = new ArrayList<>(orderedWavFiles.size());
        for (Path wav : orderedWavFiles) {
            if (wav == null) {
                throw new MediaPipelineException(AUDIO_INVALID, "Chapter contains a missing audio segment");
            }
            Path input = normalize(wav);
            AudioValidationResult validation = validate(input);
            if (!validation.valid()) {
                throw new MediaPipelineException(validation.errorCode(),
                        "Chapter audio segment is invalid: " + validation.errorCode());
            }
            inputs.add(input);
        }

        Path parent = output.getParent();
        try {
            ensureNoSymbolicLinkComponents(parent);
            Files.createDirectories(parent);
            ensureOutputCanBeWritten(output);
            Path temporary = Files.createTempFile(parent, "." + output.getFileName() + "-", ".mp3");
            boolean preserveTemporary = false;
            try {
                MediaToolRunner.CommandResult result = run(mergeArguments(inputs, temporary, metadata));
                if (result.timedOut()) {
                    throw new MediaPipelineException(MEDIA_TOOL_TIMEOUT, "ffmpeg merge timed out");
                }
                if (!result.succeeded()) {
                    throw new MediaPipelineException(MEDIA_MERGE_FAILED, "ffmpeg could not merge the chapter");
                }
                if (containsSymbolicLink(temporary)
                        || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(temporary) <= 0) {
                    throw new MediaPipelineException(MEDIA_MERGE_FAILED,
                            "ffmpeg did not produce a regular chapter file");
                }
                AudioValidationResult mergedValidation = validate(temporary);
                if (!mergedValidation.valid()) {
                    throw new MediaPipelineException(mergedValidation.errorCode(),
                            "Merged chapter audio is invalid: " + mergedValidation.errorCode());
                }
                try {
                    promote(temporary, output);
                } catch (MediaPipelineException exception) {
                    preserveTemporary = MEDIA_PROMOTION_UNSUPPORTED.equals(exception.code());
                    throw exception;
                }
                if (containsSymbolicLink(output)
                        || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MediaPipelineException(MEDIA_MERGE_FAILED,
                            "Merged chapter path is not a regular file");
                }
                return output;
            } finally {
                if (!preserveTemporary) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (MediaPipelineException exception) {
            throw exception;
        } catch (ToolUnavailableException exception) {
            throw new MediaPipelineException(MEDIA_TOOL_UNAVAILABLE, exception.getMessage(), exception);
        } catch (IOException | RuntimeException exception) {
            throw new MediaPipelineException(MEDIA_MERGE_FAILED,
                    "Unable to store the merged chapter audio", exception);
        }
    }

    private MediaToolRunner.CommandResult run(List<String> arguments)
            throws IOException, ToolUnavailableException {
        try {
            return toolRunner.run(arguments, timeout);
        } catch (IOException exception) {
            throw new ToolUnavailableException("Media tool could not be started", exception);
        } catch (RuntimeException exception) {
            throw new ToolUnavailableException("Media tool could not be started", exception);
        }
    }

    private List<String> probeArguments(Path path) {
        return List.of(ffprobeExecutable, "-v", "error", "-show_entries",
                "format=duration,format_name:stream=codec_type,codec_name,sample_rate,channels",
                "-of", "json", path.toString());
    }

    private List<String> decodeArguments(Path path) {
        return List.of(ffmpegExecutable, "-v", "error", "-xerror", "-i", path.toString(),
                "-f", "null", "-");
    }

    private List<String> mergeArguments(List<Path> inputs, Path output, ChapterMetadata metadata) {
        List<String> arguments = new ArrayList<>();
        arguments.add(ffmpegExecutable);
        arguments.add("-v");
        arguments.add("error");
        arguments.add("-y");
        for (Path input : inputs) {
            arguments.add("-i");
            arguments.add(input.toString());
        }

        String filter = concatFilter(inputs.size(), metadata);
        arguments.add("-filter_complex");
        arguments.add(filter);
        arguments.add("-map");
        arguments.add("[chapterout]");
        arguments.add("-ar");
        arguments.add(Integer.toString(metadata.sampleRate()));
        arguments.add("-ac");
        arguments.add(Integer.toString(metadata.channels()));
        arguments.add("-c:a");
        arguments.add("libmp3lame");
        arguments.add("-f");
        arguments.add("mp3");
        arguments.add("-id3v2_version");
        arguments.add("3");
        arguments.add("-write_id3v1");
        arguments.add("1");
        arguments.add("-map_metadata");
        arguments.add("-1");
        arguments.add("-metadata");
        arguments.add("track=" + String.format(Locale.ROOT, "%03d", metadata.chapterNumber()));
        arguments.add("-metadata");
        arguments.add("title=" + metadataText(metadata.title()));
        arguments.add("-metadata");
        arguments.add("album=" + metadataText(metadata.bookTitle()));
        arguments.add(output.toString());
        return List.copyOf(arguments);
    }

    private String concatFilter(int inputCount, ChapterMetadata metadata) {
        StringBuilder filter = new StringBuilder();
        String layout = channelLayout(metadata.channels());
        for (int index = 0; index < inputCount; index++) {
            filter.append('[').append(index).append(":a]")
                    .append("aresample=").append(metadata.sampleRate())
                    .append(",aformat=channel_layouts=").append(layout);
            if (metadata.atempo() != null) {
                filter.append(",atempo=").append(formatNumber(metadata.atempo()));
            }
            if (metadata.loudnorm()) {
                filter.append(",loudnorm");
            }
            filter.append("[a").append(index).append("]; ");
        }
        for (int index = 0; index < inputCount; index++) {
            filter.append("[a").append(index).append(']');
        }
        filter.append("concat=n=").append(inputCount).append(":v=0:a=1[chapterout]");
        return filter.toString();
    }

    private String channelLayout(int channels) {
        return switch (channels) {
            case 1 -> "mono";
            case 2 -> "stereo";
            case 6 -> "5.1";
            case 8 -> "7.1";
            default -> "mono";
        };
    }

    private String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private String metadataText(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    }

    private ProbeMetadata parseProbe(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidProbeException("ffprobe returned malformed metadata");
            }
            JsonNode formatNode = root.path("format");
            String formatName = text(formatNode.path("format_name"));
            JsonNode streams = root.path("streams");
            if (!streams.isArray() || streams.isEmpty()) {
                throw new InvalidProbeException("ffprobe returned no audio stream");
            }
            JsonNode audioStream = null;
            for (JsonNode stream : streams) {
                String codecType = text(stream.path("codec_type"));
                if (!"audio".equalsIgnoreCase(codecType)) {
                    continue;
                }
                if (audioStream != null) {
                    throw new InvalidProbeException("ffprobe returned multiple ambiguous audio streams");
                }
                audioStream = stream;
            }
            if (audioStream == null) {
                throw new InvalidProbeException("ffprobe returned no audio stream");
            }
            String codec = text(audioStream.path("codec_name"));
            int sampleRate = positiveInt(audioStream.path("sample_rate"));
            int channels = positiveInt(audioStream.path("channels"));
            if (codec == null || sampleRate <= 0 || channels <= 0) {
                throw new InvalidProbeException("ffprobe audio stream is missing required fields");
            }
            double duration = streamDuration(audioStream);
            if (!Double.isFinite(duration) || duration <= 0) {
                duration = decimal(formatNode.path("duration"));
            }
            if (!Double.isFinite(duration) || duration <= 0) {
                throw new InvalidProbeException("ffprobe returned a non-positive audio duration");
            }
            return new ProbeMetadata(formatName, codec, sampleRate, channels, duration);
        } catch (InvalidProbeException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new InvalidProbeException("ffprobe returned malformed metadata", exception);
        }
    }

    private double streamDuration(JsonNode stream) {
        if (stream != null && stream.isObject()) {
            double duration = decimal(stream.path("duration"));
            if (Double.isFinite(duration) && duration > 0) {
                return duration;
            }
        }
        return Double.NaN;
    }

    private double decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isBlank() ? null : text;
    }

    private int positiveInt(JsonNode value) {
        String text = text(value);
        if (text == null) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(text);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private Path normalizeOutput(Path output) {
        if (output == null) {
            throw new MediaPipelineException("OUTPUT_PATH_INVALID", "Output path is invalid");
        }
        Path normalized = normalize(output);
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            throw new MediaPipelineException("OUTPUT_PATH_INVALID", "Output path must end with .mp3");
        }
        return normalized;
    }

    private void ensureOutputCanBeWritten(Path output) {
        try {
            ensureNoSymbolicLinkComponents(output.getParent());
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(output)
                    || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS))) {
                throw new MediaPipelineException("OUTPUT_PATH_INVALID",
                        "Output path is not a regular file");
            }
        } catch (MediaPipelineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaPipelineException("OUTPUT_PATH_INVALID", "Output path is invalid", exception);
        }
    }

    private void ensureNoSymbolicLinkComponents(Path path) {
        if (path == null) {
            throw new MediaPipelineException("OUTPUT_PATH_INVALID", "Output path is invalid");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            current = Path.of("").toAbsolutePath().getRoot();
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new MediaPipelineException("OUTPUT_PATH_INVALID", "Output path contains a symbolic link");
            }
        }
    }

    private boolean containsSymbolicLink(Path path) {
        try {
            Path absolute = normalize(path);
            Path current = absolute.getRoot();
            if (current == null) {
                return false;
            }
            for (Path component : absolute) {
                current = current.resolve(component);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private void promote(Path temporary, Path output) throws IOException {
        try {
            fileMover.move(temporary, output);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new MediaPipelineException(MEDIA_PROMOTION_UNSUPPORTED,
                    "Atomic chapter promotion is not supported", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private Path normalizeRoot(Path root) {
        return root == null ? null : normalize(root);
    }

    private boolean within(Path path, Path root) {
        return root != null && path.startsWith(root);
    }

    private AudioValidationResult invalid(String code, String message) {
        return AudioValidationResult.invalid(code, message);
    }

    private String executable(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private boolean supportedAudio(ProbeMetadata metadata) {
        if (metadata.sampleRate() < MIN_SAMPLE_RATE || metadata.sampleRate() > MAX_SAMPLE_RATE
                || metadata.channels() <= 0 || metadata.channels() > MAX_CHANNELS) {
            return false;
        }
        String codec = metadata.codec().toLowerCase(Locale.ROOT);
        String format = metadata.formatName() == null
                ? "" : metadata.formatName().toLowerCase(Locale.ROOT);
        if ("mp3".equals(format) || "mp3".equals(codec)) {
            return "mp3".equals(codec);
        }
        return codec.startsWith("pcm_") && (format.isBlank() || format.contains("wav"));
    }

    private record ProbeMetadata(String formatName, String codec, int sampleRate, int channels,
                                 double durationSeconds) {
    }

    @FunctionalInterface
    public interface AtomicFileMover {
        void move(Path source, Path target) throws IOException;
    }

    private static final class InvalidProbeException extends RuntimeException {
        private InvalidProbeException(String message) {
            super(message);
        }

        private InvalidProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ToolUnavailableException extends IOException {
        private ToolUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class MediaPipelineException extends RuntimeException {
        private final String code;

        public MediaPipelineException(String code, String message) {
            super(message);
            this.code = code;
        }

        public MediaPipelineException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record ChapterMetadata(
            int chapterNumber,
            String title,
            String bookTitle,
            int sampleRate,
            int channels,
            Double atempo,
            boolean loudnorm) {

        public ChapterMetadata {
            if (chapterNumber <= 0) {
                throw new IllegalArgumentException("chapterNumber must be positive");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            if (bookTitle == null || bookTitle.isBlank()) {
                throw new IllegalArgumentException("bookTitle must not be blank");
            }
            if (sampleRate <= 0 || channels <= 0) {
                throw new IllegalArgumentException("sampleRate and channels must be positive");
            }
            if (atempo != null && (!Double.isFinite(atempo) || atempo < 0.5 || atempo > 2.0)) {
                throw new IllegalArgumentException("atempo must be between 0.5 and 2.0 when present");
            }
        }

        public ChapterMetadata(int chapterNumber, String title, String bookTitle) {
            this(chapterNumber, title, bookTitle, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS, null, false);
        }

        public ChapterMetadata(String bookTitle, int chapterNumber, String title) {
            this(chapterNumber, title, bookTitle);
        }

        public ChapterMetadata(int chapterNumber, String title, String bookTitle,
                               int sampleRate, int channels) {
            this(chapterNumber, title, bookTitle, sampleRate, channels, null, false);
        }

        public ChapterMetadata withAtempo(Double configuredAtempo) {
            return new ChapterMetadata(chapterNumber, title, bookTitle, sampleRate, channels,
                    configuredAtempo, loudnorm);
        }

        public ChapterMetadata withLoudnorm(boolean configuredLoudnorm) {
            return new ChapterMetadata(chapterNumber, title, bookTitle, sampleRate, channels,
                    atempo, configuredLoudnorm);
        }
    }
}
