package com.audiobookfactory.control;

import com.audiobookfactory.control.audio.FfmpegMediaService;
import com.audiobookfactory.control.job.JobService;
import com.audiobookfactory.control.library.LibraryPublishService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
class MvpContractTest {

    private static final String ENROLLMENT_TOKEN = "mvp-test-enroll-token";
    private static final Path STORAGE_ROOT = Path.of("target/test-storage-mvp");
    private static final Path LIBRARY_ROOT = STORAGE_ROOT.resolve("library");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FfmpegMediaService mediaService;

    @Autowired
    JobService jobService;

    @org.springframework.boot.test.mock.mockito.MockBean
    LibraryPublishService libraryPublishService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.access-token", () -> "");
        registry.add("app.worker-enroll-token", () -> ENROLLMENT_TOKEN);
        registry.add("app.storage-root", STORAGE_ROOT::toString);
        registry.add("audiobookshelf.library-root", LIBRARY_ROOT::toString);
    }

    @BeforeEach
    void cleanState() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE audio_asset, generation_job, chapter, book_version, "
                + "book, voice_profile, tts_preset, worker_registration RESTART IDENTITY CASCADE");
        deleteTree(STORAGE_ROOT);
        doAnswer(this::publishWithLocalFfmpeg).when(libraryPublishService).publishChapter(any(), any());
    }

    @Test
    void fakeWorkerCompletesOneChapterAndExposesPublishedAudio() throws Exception {
        byte[] epub = Files.readAllBytes(Path.of("examples/mvp-sample.epub"));
        MvcResult imported = mockMvc.perform(multipart("/api/v1/books")
                        .file(new MockMultipartFile(
                                "file", "mvp-sample.epub", "application/epub+zip", epub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chapterCount").value(2))
                .andReturn();
        long bookId = objectMapper.readTree(imported.getResponse().getContentAsString())
                .path("bookId").asLong(0);
        assertThat(bookId).isPositive();

        String generationRequest = objectMapper.writeValueAsString(Map.of(
                "chapterStart", 1,
                "chapterEnd", 1,
                "preset", Map.of(
                        "provider", "qwen3-tts",
                        "engine", "qwen3-tts",
                        "model", "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
                        "modelVersion", "1.0",
                        "voice", "default",
                        "language", "zh-CN",
                        "outputFormat", "wav")));
        mockMvc.perform(post("/api/v1/books/{bookId}/generation", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generationRequest))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.chapterStart").value(1))
                .andExpect(jsonPath("$.chapterEnd").value(1));

        MvcResult registration = mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer " + ENROLLMENT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerName\":\"mvp-fake-worker\",\"runtime\":{\"gpu\":\"fake\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        String workerToken = objectMapper.readTree(registration.getResponse().getContentAsString())
                .path("workerToken").asText();
        assertThat(workerToken).isNotBlank();

        byte[] wav = validWav();
        String sha256 = sha256(wav);
        int claimedJobs = 0;
        while (true) {
            MvcResult claim = mockMvc.perform(post("/api/v1/workers/claim")
                            .header("Authorization", "Bearer " + workerToken))
                    .andReturn();
            if (claim.getResponse().getStatus() == 204) {
                break;
            }
            assertThat(claim.getResponse().getStatus()).isEqualTo(200);
            JsonNode claimBody = objectMapper.readTree(claim.getResponse().getContentAsString());
            long jobId = claimBody.path("jobId").asLong(0);
            assertThat(jobId).isPositive();

            mockMvc.perform(multipart("/api/v1/workers/jobs/{jobId}/result", jobId)
                            .file(new MockMultipartFile("audio", "segment.wav", "audio/wav", wav))
                            .param("metadata", objectMapper.writeValueAsString(Map.of(
                                    "sha256", sha256,
                                    "sizeBytes", wav.length,
                                    "format", "wav")))
                            .header("Authorization", "Bearer " + workerToken))
                    .andExpect(status().isNoContent());
            claimedJobs++;
            assertThat(claimedJobs).as("single chapter job count").isLessThan(20);
        }
        assertThat(claimedJobs).isPositive();

        mockMvc.perform(get("/api/v1/books/{bookId}/progress", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedChapters").value(1))
                .andExpect(jsonPath("$.totalChapters").value(2))
                .andExpect(jsonPath("$.currentChapter").value(2));

        MvcResult chapters = mockMvc.perform(get("/api/v1/books/{bookId}/chapters", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].audioUrl").value("/api/v1/chapters/1/audio"))
                .andReturn();
        JsonNode chapter = objectMapper.readTree(chapters.getResponse().getContentAsString()).get(0);
        long chapterId = chapter.path("id").asLong(0);
        assertThat(chapterId).isPositive();
        assertThat(Files.exists(LIBRARY_ROOT.resolve("mvp-sample").resolve("001 - chapter.mp3")))
                .isTrue();

        mockMvc.perform(get("/api/v1/chapters/{chapterId}/audio", chapterId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("audio/mpeg")));
    }

    private Object publishWithLocalFfmpeg(InvocationOnMock invocation) {
        LibraryPublishService.Chapter chapter = invocation.getArgument(0);
        @SuppressWarnings("unchecked")
        List<LibraryPublishService.AudioAsset> assets = invocation.getArgument(1);
        Path output = LIBRARY_ROOT.resolve("mvp-sample")
                .resolve(String.format(Locale.ROOT, "%03d - chapter.mp3", chapter.chapterNumber()));
        Path merged = mediaService.mergeChapter(
                assets.stream().map(LibraryPublishService.AudioAsset::path).toList(),
                output,
                new FfmpegMediaService.ChapterMetadata(
                        chapter.chapterNumber(), chapter.title(), chapter.bookTitle()));
        jobService.completeChapterAfterMerge(chapter.chapterId(), merged.toString());
        return merged;
    }

    private static byte[] validWav() {
        int sampleRate = 24_000;
        int frames = 2_400;
        int dataSize = frames * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        for (int index = 0; index < frames; index++) {
            buffer.putShort((short) 0);
        }
        return buffer.array();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
