package com.audiobookfactory.control.audio;

import com.audiobookfactory.control.library.LibraryPublishService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegMediaServiceTest {

    private static final String PROBE_JSON = """
            {"format":{"format_name":"wav","duration":"1.25"},"streams":[{"codec_type":"audio","codec_name":"pcm_s16le","sample_rate":"24000","channels":1}]}
            """;
    private static final String MP3_PROBE_JSON = """
            {"format":{"format_name":"mp3","duration":"1.25"},"streams":[{"codec_type":"audio","codec_name":"mp3","sample_rate":"24000","channels":1}]}
            """;

    @Test
    void rejectsMissingAudio() throws IOException {
        FakeMediaToolRunner runner = new FakeMediaToolRunner();
        FfmpegMediaService mediaService = new FfmpegMediaService(runner);

        AudioValidationResult result = mediaService.validate(Path.of("missing.wav"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("AUDIO_INVALID");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void validatesRegularAudioWithProbeDecodeAndSha() throws IOException {
        Path directory = Files.createTempDirectory("audio-validation-");
        Path wav = directory.resolve("voice.wav");
        Files.write(wav, new byte[2048]);
        FakeMediaToolRunner runner = new FakeMediaToolRunner();
        FfmpegMediaService mediaService = new FfmpegMediaService(runner);

        AudioValidationResult result = mediaService.validate(wav);

        assertThat(result.valid()).isTrue();
        assertThat(result.codec()).isEqualTo("pcm_s16le");
        assertThat(result.sampleRate()).isEqualTo(24_000);
        assertThat(result.channels()).isEqualTo(1);
        assertThat(result.durationSeconds()).isEqualTo(1.25);
        assertThat(result.sizeBytes()).isEqualTo(2048);
        assertThat(result.sha256()).hasSize(64);
        assertThat(runner.calls()).extracting(call -> call.get(0))
                .containsExactly("ffprobe", "ffmpeg");
    }

    @Test
    void probesFormatAndStreamTypeBeforeAcceptingAnAudioAsset() throws IOException {
        Path wav = writeAudio(Files.createTempDirectory("audio-probe-fields-")
                .resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner();

        new FfmpegMediaService(runner).validate(wav);

        assertThat(runner.calls().get(0)).contains(
                "format=duration,format_name:stream=codec_type,codec_name,sample_rate,channels");
    }

    @Test
    void rejectsACompressedCodecEvenWhenTheProbeFieldsArePositive() throws IOException {
        Path directory = Files.createTempDirectory("audio-codec-boundary-");
        Path wav = writeAudio(directory.resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner("""
                {"format":{"format_name":"wav","duration":"1.25"},"streams":[{"codec_type":"audio","codec_name":"aac","sample_rate":"24000","channels":1}]}
                """);

        AudioValidationResult result = new FfmpegMediaService(runner).validate(wav);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FfmpegMediaService.AUDIO_INVALID);
    }

    @Test
    void rejectsAProbeStreamThatIsNotAudio() throws IOException {
        Path directory = Files.createTempDirectory("audio-stream-boundary-");
        Path wav = writeAudio(directory.resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner("""
                {"format":{"format_name":"wav","duration":"1.25"},"streams":[{"codec_type":"video","codec_name":"pcm_s16le","sample_rate":"24000","channels":1}]}
                """);

        AudioValidationResult result = new FfmpegMediaService(runner).validate(wav);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FfmpegMediaService.AUDIO_INVALID);
    }

    @Test
    void rejectsSampleRateAndChannelCountOutsideTheConfiguredAudioRange() throws IOException {
        Path directory = Files.createTempDirectory("audio-shape-boundary-");
        Path wav = writeAudio(directory.resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner("""
                {"format":{"format_name":"wav","duration":"1.25"},"streams":[{"codec_type":"audio","codec_name":"pcm_s16le","sample_rate":"192000","channels":6}]}
                """);

        AudioValidationResult result = new FfmpegMediaService(runner).validate(wav);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FfmpegMediaService.AUDIO_INVALID);
    }

    @Test
    void usesStrictDecodeFailureMode() throws IOException {
        Path directory = Files.createTempDirectory("audio-decode-command-");
        Path wav = writeAudio(directory.resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner();

        new FfmpegMediaService(runner).validate(wav);

        List<String> decode = runner.calls().stream()
                .filter(call -> "ffmpeg".equals(call.get(0)) && !call.contains("-filter_complex"))
                .findFirst()
                .orElseThrow();
        assertThat(decode).contains("-xerror");
    }

    @Test
    void rejectsAnAtempoValueOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new FfmpegMediaService.ChapterMetadata(
                1, "第一章", "测试书").withAtempo(0.25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnAudioInputOutsideTheConfiguredStorageRoot() throws IOException {
        Path storageRoot = Files.createTempDirectory("audio-storage-root-");
        Path outside = writeAudio(Files.createTempDirectory("audio-outside-root-")
                .resolve("voice.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner();

        AudioValidationResult result = new FfmpegMediaService(
                runner, storageRoot, storageRoot).validate(outside);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FfmpegMediaService.AUDIO_INVALID);
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void rejectsAnOutputOutsideTheConfiguredLibraryRoot() throws IOException {
        Path storageRoot = Files.createTempDirectory("audio-storage-root-");
        Path libraryRoot = Files.createTempDirectory("audio-library-root-");
        Path input = writeAudio(storageRoot.resolve("voice.wav"));
        Path outside = Files.createTempDirectory("audio-output-outside-").resolve("chapter.mp3");
        FakeMediaToolRunner runner = new FakeMediaToolRunner();

        assertThatThrownBy(() -> new FfmpegMediaService(
                runner, storageRoot, libraryRoot).mergeChapter(
                        List.of(input), outside,
                        new FfmpegMediaService.ChapterMetadata(1, "第一章", "测试书")))
                .isInstanceOf(FfmpegMediaService.MediaPipelineException.class)
                .extracting("code")
                .isEqualTo("OUTPUT_PATH_INVALID");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void refusesNonAtomicPromotionAndLeavesTheTemporaryFileForRecovery() throws IOException {
        Path root = Files.createTempDirectory("audio-promotion-");
        Path input = writeAudio(root.resolve("voice.wav"));
        Path output = root.resolve("chapter.mp3");
        FakeMediaToolRunner runner = new FakeMediaToolRunner();
        FfmpegMediaService.AtomicFileMover mover = (source, target) -> {
            throw new java.nio.file.AtomicMoveNotSupportedException(
                    source.toString(), target.toString(), "atomic move unavailable");
        };
        FfmpegMediaService mediaService = new FfmpegMediaService(runner, root, root, mover);

        assertThatThrownBy(() -> mediaService.mergeChapter(
                List.of(input), output,
                new FfmpegMediaService.ChapterMetadata(1, "第一章", "测试书")))
                .isInstanceOf(FfmpegMediaService.MediaPipelineException.class)
                .extracting("code")
                .isEqualTo(FfmpegMediaService.MEDIA_PROMOTION_UNSUPPORTED);
        assertThat(output).doesNotExist();
        try (var files = Files.list(root)) {
            assertThat(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("." + output.getFileName() + "-"))).isTrue();
        }
    }

    @Test
    void rejectsAnAncestorSymlinkBeforeCreatingAPathOutsideTheLibraryRoot() throws IOException {
        Path parent = Files.createTempDirectory("library-link-parent-");
        Path outside = Files.createTempDirectory("library-link-target-");
        Path link = parent.resolve("library-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("symbolic links are unavailable in this environment");
            return;
        } catch (IOException exception) {
            Assumptions.abort("symbolic links are unavailable in this environment: " + exception.getMessage());
            return;
        }

        Path libraryRoot = link.resolve("library");
        LibraryPublishService publisher = new LibraryPublishService(
                new FfmpegMediaService(new FakeMediaToolRunner()), libraryRoot, ignored -> { });

        assertThatThrownBy(() -> publisher.publishChapter(
                new LibraryPublishService.Chapter("测试书", 1, "第一章"),
                List.of(new LibraryPublishService.AudioAsset(1, parent.resolve("missing.wav")))))
                .isInstanceOf(LibraryPublishService.PublishException.class)
                .extracting("code")
                .isEqualTo("PUBLISH_PATH_INVALID");
        assertThat(outside.resolve("library")).doesNotExist();
    }

    @Test
    void mergesSegmentsInOrderAndLeavesLoudnormDisabledByDefault() throws IOException {
        Path directory = Files.createTempDirectory("audio-merge-");
        Path first = writeAudio(directory.resolve("segment-10.wav"));
        Path second = writeAudio(directory.resolve("segment-2.wav"));
        Path output = directory.resolve("chapter.mp3");
        FakeMediaToolRunner runner = new FakeMediaToolRunner();
        FfmpegMediaService mediaService = new FfmpegMediaService(runner);

        Path merged = mediaService.mergeChapter(
                List.of(first, second), output,
                new FfmpegMediaService.ChapterMetadata(2, "第二章", "测试书"));

        assertThat(merged).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(Files.isRegularFile(output)).isTrue();
        List<String> ffmpeg = runner.calls().stream()
                .filter(call -> "ffmpeg".equals(call.get(0)) && call.contains("-filter_complex"))
                .findFirst()
                .orElseThrow();
        assertThat(ffmpeg).containsSubsequence("-i", first.toAbsolutePath().toString(),
                "-i", second.toAbsolutePath().toString());
        assertThat(ffmpeg).containsSubsequence("-ar", "24000", "-ac", "1",
                "-id3v2_version", "3", "-metadata", "track=002",
                "-metadata", "title=第二章", "-metadata", "album=测试书");
        assertThat(ffmpeg).doesNotContain("loudnorm");
    }

    @Test
    void publishesAChapterWithSafeNameAndScansAfterMerge() throws IOException {
        Path libraryRoot = Files.createTempDirectory("audiobookshelf-library-");
        Path first = writeAudio(libraryRoot.resolve("segment-1.wav"));
        Path second = writeAudio(libraryRoot.resolve("segment-2.wav"));
        FakeMediaToolRunner runner = new FakeMediaToolRunner();
        FfmpegMediaService mediaService = new FfmpegMediaService(runner);
        LibraryPublishService.Chapter chapter =
                new LibraryPublishService.Chapter("测试书", 1, "第一/章");
        LibraryPublishService.AudioAsset firstAsset =
                new LibraryPublishService.AudioAsset(1, first);
        LibraryPublishService.AudioAsset secondAsset =
                new LibraryPublishService.AudioAsset(2, second);

        LibraryPublishService publisher = new LibraryPublishService(
                mediaService, libraryRoot, ignored -> { });

        Path published = publisher.publishChapter(chapter, List.of(secondAsset, firstAsset));

        assertThat(published).isEqualTo(libraryRoot.resolve("测试书")
                .resolve("001 - 第一_章.mp3").toAbsolutePath().normalize());
        assertThat(Files.isRegularFile(published)).isTrue();
        assertThat(runner.calls().stream().filter(call -> "ffmpeg".equals(call.get(0))).findFirst())
                .isPresent();
    }

    @Test
    void rejectsNullChapterAssetWithClassifiedError() throws IOException {
        Path libraryRoot = Files.createTempDirectory("audiobookshelf-invalid-assets-");
        FfmpegMediaService mediaService = new FfmpegMediaService(new FakeMediaToolRunner());
        LibraryPublishService publisher = new LibraryPublishService(
                mediaService, libraryRoot, ignored -> { });

        assertThatThrownBy(() -> publisher.publishChapter(
                new LibraryPublishService.Chapter("测试书", 1, "第一章"),
                Arrays.asList(
                        new LibraryPublishService.AudioAsset(1, libraryRoot.resolve("missing.wav")),
                        null)))
                .isInstanceOf(LibraryPublishService.PublishException.class)
                .extracting("code")
                .isEqualTo("AUDIO_INVALID");
    }

    private Path writeAudio(Path path) throws IOException {
        Files.write(path, new byte[2048]);
        return path;
    }

    private static final class FakeMediaToolRunner extends MediaToolRunner {

        private final List<List<String>> calls = new ArrayList<>();
        private final String probeJson;

        private FakeMediaToolRunner() {
            this(PROBE_JSON);
        }

        private FakeMediaToolRunner(String probeJson) {
            this.probeJson = probeJson;
        }

        @Override
        public CommandResult run(List<String> arguments, Duration timeout) throws IOException {
            calls.add(List.copyOf(arguments));
            String executable = Path.of(arguments.get(0)).getFileName().toString();
            if ("ffprobe".equalsIgnoreCase(executable)) {
                String path = arguments.get(arguments.size() - 1);
                return new CommandResult(0, path.toLowerCase().endsWith(".mp3")
                        ? MP3_PROBE_JSON : probeJson, "", false);
            }
            if ("ffmpeg".equalsIgnoreCase(executable)) {
                if (arguments.contains("-filter_complex")) {
                    Path output = Path.of(arguments.get(arguments.size() - 1));
                    Files.write(output, new byte[2048]);
                }
                return new CommandResult(0, "", "", false);
            }
            return new CommandResult(127, "", "unknown executable", false);
        }

        private List<List<String>> calls() {
            return List.copyOf(calls);
        }
    }
}
