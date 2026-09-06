package com.audiobookfactory.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.access-token=")
class ContextLoadTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void applicationContextStarts() {
        assertThat(context).isNotNull();
    }

    @Test
    void actuatorHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayMigrationCreatesRequiredColumnsConstraintsAndIndexes() {
        assertThat(tableNames())
                .contains("book", "book_version", "chapter", "voice_profile", "tts_preset",
                        "generation_job", "audio_asset", "worker_registration")
                .doesNotContain("user", "admin", "role");

        assertThat(columnsFor("book"))
                .containsExactlyInAnyOrder("id", "title", "author", "cover_path", "status",
                        "active_scope_id", "created_at", "updated_at");
        assertThat(columnsFor("book_version"))
                .containsExactlyInAnyOrder("id", "book_id", "source_file_path", "source_file_sha256",
                        "parser_version", "segmentation_rule_version", "created_at");
        assertThat(columnsFor("chapter"))
                .containsExactlyInAnyOrder("id", "book_version_id", "chapter_number", "title", "text_path",
                        "text_sha256", "status", "final_audio_path", "created_at", "updated_at");
        assertThat(columnsFor("voice_profile"))
                .containsExactlyInAnyOrder("id", "name", "reference_audio_path", "reference_audio_sha256",
                        "reference_text", "voice_design_description", "created_at", "updated_at");
        assertThat(columnsFor("tts_preset"))
                .containsExactlyInAnyOrder("id", "engine", "model", "model_version", "style_instruction",
                        "speed", "model_parameters", "segment_length", "created_at", "updated_at");
        assertThat(columnsFor("generation_job"))
                .containsExactlyInAnyOrder("id", "chapter_id", "segment_index", "segment_text", "text_sha256",
                        "preset_snapshot", "run_id", "batch_id", "scope_id", "status", "lease_owner",
                        "lease_expires_at", "error_code",
                        "error_message", "heartbeat_at", "attempts", "next_retry_at", "started_at",
                        "finished_at", "result_idempotency_key", "created_at", "updated_at");
        assertThat(columnsFor("audio_asset"))
                .containsExactlyInAnyOrder("id", "job_id", "file_path", "format", "duration_ms", "sample_rate",
                        "channels", "size_bytes", "sha256", "created_at");
        assertThat(columnsFor("worker_registration"))
                .containsExactlyInAnyOrder("worker_id", "name", "capabilities", "token_hash", "status",
                        "last_heartbeat_at", "created_at", "updated_at");

        assertThat(foreignKeys()).contains(
                new ForeignKey("book_version", "book_id", "book", "id"),
                new ForeignKey("chapter", "book_version_id", "book_version", "id"),
                new ForeignKey("generation_job", "chapter_id", "chapter", "id"),
                new ForeignKey("audio_asset", "job_id", "generation_job", "id"));

        assertThat(indexNames()).contains(
                "idx_book_status",
                "idx_book_version_book_id",
                "idx_chapter_book_version_id",
                "idx_chapter_status",
                "idx_generation_job_status_lease",
                "idx_generation_job_chapter_id",
                "idx_generation_job_claim",
                "idx_generation_job_scope_claim",
                "idx_audio_asset_job_id",
                "idx_worker_registration_status");

        Map<String, String> indexDefinitions = indexDefinitions();
        assertThat(indexDefinitions.get("idx_book_status")).contains("(status)");
        assertThat(indexDefinitions.get("idx_book_version_book_id")).contains("(book_id)");
        assertThat(indexDefinitions.get("idx_chapter_book_version_id")).contains("(book_version_id)");
        assertThat(indexDefinitions.get("idx_chapter_status")).contains("(status)");
        assertThat(indexDefinitions.get("idx_generation_job_status_lease"))
                .contains("(status, lease_expires_at)");
        assertThat(indexDefinitions.get("idx_generation_job_chapter_id")).contains("(chapter_id)");
        assertThat(indexDefinitions.get("idx_generation_job_claim"))
                .contains("(status, next_retry_at, chapter_id, segment_index)");
        assertThat(indexDefinitions.get("idx_generation_job_scope_claim"))
                .contains("(scope_id, status, next_retry_at, chapter_id, segment_index)");
        assertThat(indexDefinitions.get("idx_audio_asset_job_id")).contains("(job_id)");
        assertThat(indexDefinitions.get("idx_worker_registration_status")).contains("(status)");
    }

    private Set<String> tableNames() {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                (resultSet, rowNum) -> resultSet.getString("table_name")));
    }

    private Set<String> columnsFor(String tableName) {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                (resultSet, rowNum) -> resultSet.getString("column_name"),
                tableName));
    }

    private List<ForeignKey> foreignKeys() {
        return jdbcTemplate.query(
                "SELECT tc.table_name, kcu.column_name, ccu.table_name AS foreign_table_name, "
                        + "ccu.column_name AS foreign_column_name "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "ON tc.constraint_schema = kcu.constraint_schema "
                        + "AND tc.constraint_name = kcu.constraint_name "
                        + "AND tc.table_name = kcu.table_name "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "ON tc.constraint_schema = ccu.constraint_schema "
                        + "AND tc.constraint_name = ccu.constraint_name "
                        + "WHERE tc.constraint_schema = 'public' AND tc.constraint_type = 'FOREIGN KEY'",
                (resultSet, rowNum) -> new ForeignKey(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name"),
                        resultSet.getString("foreign_table_name"),
                        resultSet.getString("foreign_column_name")));
    }

    private Set<String> indexNames() {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                (resultSet, rowNum) -> resultSet.getString("indexname")));
    }

    private Map<String, String> indexDefinitions() {
        return jdbcTemplate.query(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'",
                resultSet -> {
                    Map<String, String> definitions = new HashMap<>();
                    while (resultSet.next()) {
                        definitions.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
                    }
                    return definitions;
                });
    }

    private record ForeignKey(
            String tableName,
            String columnName,
            String referencedTableName,
            String referencedColumnName) {
    }
}
