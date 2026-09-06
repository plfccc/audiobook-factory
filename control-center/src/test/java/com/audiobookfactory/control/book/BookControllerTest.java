package com.audiobookfactory.control.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
class BookControllerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.access-token", () -> "");
        registry.add("app.worker-enroll-token", () -> "test-enroll-token");
        registry.add("app.storage-root", () -> "target/test-storage-book-api");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE audio_asset, generation_job, chapter, book_version, "
                + "book, voice_profile, tts_preset, worker_registration RESTART IDENTITY CASCADE");
    }

    @Test
    void importsBookOnceAndExposesChapterAsTheMainProgressUnit() throws Exception {
        byte[] epub = Files.readAllBytes(Path.of("src/test/resources/fixtures/epub/sample.epub"));

        MvcResult imported = mockMvc.perform(multipart("/api/v1/books")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "sample.epub", "application/epub+zip", epub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").isNumber())
                .andExpect(jsonPath("$.bookVersionId").isNumber())
                .andExpect(jsonPath("$.chapterCount").value(2))
                .andReturn();

        String body = imported.getResponse().getContentAsString();
        long bookId = Long.parseLong(body.replaceAll(".*\\\"bookId\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/v1/books/{bookId}", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookId))
                .andExpect(jsonPath("$.title").value("sample"));
        mockMvc.perform(get("/api/v1/books/{bookId}/chapters", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chapterNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("WAITING"))
                .andExpect(jsonPath("$[0].segmentCount").isNumber());
        mockMvc.perform(get("/api/v1/books/{bookId}/progress", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedChapters").value(0))
                .andExpect(jsonPath("$.totalChapters").value(2))
                .andExpect(jsonPath("$.currentChapter").value(1));

        mockMvc.perform(get("/api/v1/books/{bookId}/chapters/1/segments", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").isString())
                .andExpect(jsonPath("$[0].segmentIndex").value(1))
                .andExpect(jsonPath("$[0].text").isString())
                .andExpect(jsonPath("$[0].preset").doesNotExist());

        mockMvc.perform(post("/api/v1/books/{bookId}/generation", bookId)
                        .contentType("application/json")
                        .content("""
                                {"chapterStart":1,"chapterEnd":1,"preset":{
                                  "runId":"run-a","batchId":"batch-a","scopeId":"scope-a",
                                  "provider":"qwen3-tts",
                                  "model":"Qwen/Qwen3-TTS-12Hz-1.7B-Base",
                                  "modelVersion":"1.0",
                                  "voice":"default",
                                  "language":"zh-CN",
                                  "outputFormat":"wav",
                                  "modelParameters":{"temperature":0.7,"topP":0.9},
                                  "clone_prompt":"must-not-be-persisted",
                                  "secret":"must-not-be-persisted",
                                  "token":"must-not-be-persisted",
                                  "password":"must-not-be-persisted",
                                  "apiKey":"must-not-be-persisted",
                                  "accessToken":"must-not-be-persisted",
                                  "enrollmentToken":"must-not-be-persisted"
                                }}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobIds").isArray())
                .andExpect(jsonPath("$.runId").value("run-a"))
                .andExpect(jsonPath("$.batchId").value("batch-a"))
                .andExpect(jsonPath("$.scopeId").value("scope-a"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM book WHERE id=?", String.class, bookId))
                .isEqualTo("RUNNING");
        String presetSnapshot = jdbcTemplate.queryForObject(
                "SELECT preset_snapshot::text FROM generation_job WHERE chapter_id=1 ORDER BY id LIMIT 1",
                String.class);
        assertThat(presetSnapshot)
                .doesNotContain("clone_prompt", "secret", "token", "password", "apiKey",
                        "accessToken", "enrollmentToken", "must-not-be-persisted")
                .contains("modelParameters", "temperature", "0.7", "topP", "0.9");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM generation_job WHERE chapter_id=1 ORDER BY id LIMIT 1", String.class))
                .isEqualTo("run-a");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT batch_id FROM generation_job WHERE chapter_id=1 ORDER BY id LIMIT 1", String.class))
                .isEqualTo("batch-a");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scope_id FROM generation_job WHERE chapter_id=1 ORDER BY id LIMIT 1", String.class))
                .isEqualTo("scope-a");

        mockMvc.perform(post("/api/v1/books/{bookId}/pause", bookId))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM book WHERE id=?", String.class, bookId))
                .isEqualTo("PAUSED");
        mockMvc.perform(post("/api/v1/books/{bookId}/resume", bookId))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM book WHERE id=?", String.class, bookId))
                .isEqualTo("RUNNING");

        mockMvc.perform(multipart("/api/v1/books")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "renamed.epub", "application/epub+zip", epub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId").value(bookId))
                .andExpect(jsonPath("$.bookVersionId").isNumber());

        Integer bookCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Integer.class);
        Integer versionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_version", Integer.class);
        assertThat(bookCount).isEqualTo(1);
        assertThat(versionCount).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeAndMalformedBookRequestsWithStableErrorCodes() throws Exception {
        mockMvc.perform(multipart("/api/v1/books")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "../not-epub.txt", "text/plain", "not epub".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EPUB_EXTENSION"));

        mockMvc.perform(get("/api/v1/books/999999/chapters/../../segments"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void generationErrorsDoNotEchoClonePromptIntoTheErrorApi() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/books/999999/generation")
                        .contentType("application/json")
                        .content("{\"chapterStart\":1,\"chapterEnd\":1,"
                                + "\"preset\":{\"clone_prompt\":\"secret-clone-prompt\"}}"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("clone_prompt")
                .doesNotContain("secret-clone-prompt");
    }
}
