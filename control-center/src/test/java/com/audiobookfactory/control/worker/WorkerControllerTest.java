package com.audiobookfactory.control.worker;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
class WorkerControllerTest {

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
        registry.add("app.access-token", () -> "configured-ui-token");
        registry.add("app.worker-enroll-token", () -> "test-enroll-token");
        registry.add("app.storage-root", () -> "target/test-storage-worker");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE audio_asset, generation_job, chapter, book_version, "
                + "book, voice_profile, tts_preset, worker_registration RESTART IDENTITY CASCADE");
    }

    @Test
    void registerUsesEnrollmentTokenAndReturnsOnlyShortLivedWorkerCredential() throws Exception {
        String response = mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer test-enroll-token")
                        .contentType("application/json")
                        .content("""
                                {"workerName":"colab-1","runtime":{"cudaAvailable":true},"capabilities":{"engineId":"qwen3-tts"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").isString())
                .andExpect(jsonPath("$.workerToken").isString())
                .andExpect(jsonPath("$.leaseSeconds").value(300))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("test-enroll-token");
        String workerToken = response.replaceAll(".*\\\"workerToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM worker_registration", String.class))
                .doesNotContain(workerToken)
                .hasSize(64);

        mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer test-enroll-token")
                        .contentType("application/json")
                        .content("{\"workerName\":\"colab-2\",\"runtime\":{},\"capabilities\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workerProtocolUsesBearerTokenAndRejectsLostLeaseWithoutChangingTheJob() throws Exception {
        String response = mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer test-enroll-token")
                        .contentType("application/json")
                        .content("{\"workerName\":\"colab-1\",\"runtime\":{},\"capabilities\":{}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String workerToken = response.replaceAll(".*\\\"workerToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        jdbcTemplate.update("INSERT INTO book (title, status) VALUES ('Test', 'RUNNING')");
        jdbcTemplate.update("INSERT INTO book_version (book_id, source_file_path, source_file_sha256, parser_version, segmentation_rule_version) "
                + "VALUES (1, '/tmp/book.epub', ?, 'v1', 'v1')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO chapter (book_version_id, chapter_number, title, text_path, text_sha256, status) "
                + "VALUES (1, 1, 'One', '/tmp/chapter.txt', ?, 'WAITING')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, preset_snapshot, status) "
                + "VALUES (1, 1, 'Hello.', ?, '{\"provider\":\"qwen3-tts\",\"clone_prompt\":\"must-not-leak\"}'::jsonb, 'WAITING')",
                String.format("%064d", 1));

        String claim = mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.chapterIndex").value(1))
                .andExpect(jsonPath("$.segmentIndex").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(claim).doesNotContain("clone_prompt").doesNotContain("clone prompt");

        mockMvc.perform(post("/api/v1/workers/jobs/1/heartbeat")
                        .header("Authorization", "Bearer another-token")
                        .contentType("application/json")
                        .content("{\"phase\":\"generating\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/workers/jobs/1/heartbeat")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType("application/json")
                        .content("{\"phase\":\"generating\"}"))
                .andExpect(status().isNoContent());

        jdbcTemplate.update("UPDATE generation_job SET lease_expires_at=NOW() - INTERVAL '1 second' WHERE id=1");
        mockMvc.perform(post("/api/v1/workers/jobs/1/heartbeat")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType("application/json")
                        .content("{\"phase\":\"generating\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_LOST"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("LEASED");
    }

    @Test
    void noWorkReturns204AndResultRetryIsIdempotent() throws Exception {
        String registration = mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer test-enroll-token")
                        .contentType("application/json")
                        .content("{\"workerName\":\"colab-1\",\"runtime\":{},\"capabilities\":{}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String workerToken = registration.replaceAll(".*\\\"workerToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        jdbcTemplate.update("INSERT INTO book (title, status) VALUES ('Test', 'RUNNING')");
        jdbcTemplate.update("INSERT INTO book_version (book_id, source_file_path, source_file_sha256, parser_version, segmentation_rule_version) "
                + "VALUES (1, '/tmp/book.epub', ?, 'v1', 'v1')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO chapter (book_version_id, chapter_number, title, text_path, text_sha256, status) "
                + "VALUES (1, 1, 'One', '/tmp/chapter.txt', ?, 'WAITING')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, preset_snapshot, status) "
                + "VALUES (1, 1, 'Hello.', ?, '{}'::jsonb, 'WAITING')", String.format("%064d", 1));

        mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk());

        byte[] audio = "not-a-real-wav-but-a-deterministic-result".getBytes(StandardCharsets.UTF_8);
        String resultSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(audio));
        var multipart = new org.springframework.mock.web.MockMultipartFile(
                "audio", "result.wav", "audio/wav", audio);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/api/v1/workers/jobs/1/result")
                        .file(multipart)
                        .param("metadata", "{\"sha256\":\"" + resultSha256 + "\",\"sizeBytes\":" + audio.length + "}")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audio_asset WHERE job_id=1", Integer.class))
                .isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/api/v1/workers/jobs/1/result")
                        .file(multipart)
                        .param("metadata", "{\"sha256\":\"" + resultSha256 + "\",\"sizeBytes\":" + audio.length + "}")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audio_asset WHERE job_id=1", Integer.class))
                .isEqualTo(1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/api/v1/workers/jobs/1/result")
                        .file(multipart)
                        .param("metadata", "{\"sha256\":\"" + "b".repeat(64) + "\",\"sizeBytes\":" + audio.length + "}")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_IDEMPOTENCY_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audio_asset WHERE job_id=1", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failurePersistsTheStatusAndDoesNotExposeTheWorkerToken() throws Exception {
        String registration = mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer test-enroll-token")
                        .contentType("application/json")
                        .content("{\"workerName\":\"colab-1\",\"runtime\":{},\"capabilities\":{}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String workerToken = registration.replaceAll(".*\\\"workerToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        jdbcTemplate.update("INSERT INTO book (title, status) VALUES ('Test', 'RUNNING')");
        jdbcTemplate.update("INSERT INTO book_version (book_id, source_file_path, source_file_sha256, parser_version, segmentation_rule_version) "
                + "VALUES (1, '/tmp/book.epub', ?, 'v1', 'v1')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO chapter (book_version_id, chapter_number, title, text_path, text_sha256, status) "
                + "VALUES (1, 1, 'One', '/tmp/chapter.txt', ?, 'WAITING')", String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, preset_snapshot, status) "
                + "VALUES (1, 1, 'Hello.', ?, '{}'::jsonb, 'WAITING')", String.format("%064d", 1));

        mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workers/jobs/1/failure")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType("application/json")
                        .content("{\"code\":\"PERMANENT_FAILED\",\"message\":\"audio invalid\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM generation_job WHERE id=1", String.class))
                .isEqualTo("PERMANENT_FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT error_message FROM generation_job WHERE id=1", String.class))
                .isEqualTo("audio invalid");
        assertThat(registration).doesNotContain("test-enroll-token");
    }
}
