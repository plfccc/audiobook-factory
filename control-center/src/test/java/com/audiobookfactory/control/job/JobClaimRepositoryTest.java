package com.audiobookfactory.control.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobClaimRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JobClaimRepository repository;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.access-token", () -> "");
        registry.add("app.worker-enroll-token", () -> "test-enroll-token");
        registry.add("app.storage-root", () -> "target/test-storage-job");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE audio_asset, generation_job, chapter, book_version, "
                + "book, voice_profile, tts_preset, worker_registration RESTART IDENTITY CASCADE");
    }

    @Test
    void claimReturnsEarliestSegmentOfEarliestRunningChapter() {
        insertBookWithJobs("RUNNING", List.of(2, 1), List.of(1));
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        JobClaim claim = repository.claimNext("worker-1", now);

        assertThat(claim).isNotNull();
        assertThat(claim.chapterIndex()).isEqualTo(1);
        assertThat(claim.segmentIndex()).isEqualTo(1);
        assertThat(Duration.between(now, claim.leaseUntil())).isEqualTo(Duration.ofMinutes(5));
        assertThat(claim.attempts()).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsReturnedToWaitingBeforeAnotherWorkerClaimsIt() {
        insertBookWithJobs("RUNNING", List.of(1), List.of());
                jdbcTemplate.update("UPDATE generation_job SET status='LEASED', lease_owner=?, "
                        + "lease_expires_at=?, heartbeat_at=?, error_code=?, error_message=?, "
                        + "next_retry_at=? WHERE id=1",
                "dead-worker", Instant.parse("2026-09-04T23:59:00Z"),
                Instant.parse("2026-09-04T23:58:00Z"), "STALE", "stale message",
                Instant.parse("2026-09-04T23:59:30Z"));

        JobClaim claim = repository.claimNext("worker-2", Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(claim.jobId()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("LEASED");
        assertThat(jdbcTemplate.queryForObject("SELECT lease_owner FROM generation_job WHERE id=1", String.class))
                .isEqualTo("worker-2");
        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM generation_job WHERE id=1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT error_message FROM generation_job WHERE id=1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT next_retry_at FROM generation_job WHERE id=1", Instant.class))
                .isNull();
    }

    @Test
    void expiredGeneratingAndUploadingJobsAreAlsoReturnedToWaiting() {
        insertBookWithJobs("RUNNING", List.of(1), List.of());
        jdbcTemplate.update("UPDATE generation_job SET status='GENERATING', lease_owner=?, "
                        + "lease_expires_at=? WHERE id=1",
                "dead-worker", Instant.parse("2026-09-04T23:59:00Z"));

        JobClaim claim = repository.claimNext("worker-2", Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(claim).isNotNull();
        assertThat(claim.leaseOwner()).isEqualTo("worker-2");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("LEASED");
    }

    @Test
    void expiredLeaseRecoveryClearsAllLeaseRetryAndErrorMetadataBeforeClaiming() {
        insertBookWithJobs("PAUSED", List.of(1), List.of());
        jdbcTemplate.update("UPDATE generation_job SET status='UPLOADING', lease_owner=?, "
                        + "lease_expires_at=?, heartbeat_at=?, error_code=?, error_message=?, "
                        + "next_retry_at=? WHERE id=1",
                "dead-worker", Instant.parse("2026-09-04T23:59:00Z"),
                Instant.parse("2026-09-04T23:58:00Z"), "STALE", "stale message",
                Instant.parse("2026-09-04T23:59:30Z"));

        assertThat(repository.claimNext("worker-2", Instant.parse("2026-09-05T00:00:00Z"))).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_job WHERE id=1", String.class))
                .isEqualTo("WAITING");
        assertThat(jdbcTemplate.queryForObject("SELECT lease_owner FROM generation_job WHERE id=1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lease_expires_at FROM generation_job WHERE id=1", Instant.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT heartbeat_at FROM generation_job WHERE id=1", Instant.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM generation_job WHERE id=1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT error_message FROM generation_job WHERE id=1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT next_retry_at FROM generation_job WHERE id=1", Instant.class))
                .isNull();
    }

    @Test
    void concurrentWorkersClaimDifferentRowsWithoutDuplicateLease() throws Exception {
        insertBookWithJobs("RUNNING", List.of(1, 2), List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<JobClaim> first = () -> repository.claimNext("worker-a", Instant.now());
            Callable<JobClaim> second = () -> repository.claimNext("worker-b", Instant.now());
            List<Future<JobClaim>> futures = executor.invokeAll(List.of(first, second));

            assertThat(futures.get(0).get().jobId()).isNotEqualTo(futures.get(1).get().jobId());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM generation_job WHERE status='LEASED'", Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void laterChapterCannotBeClaimedUntilEveryEarlierChapterSucceeds() {
        insertBookWithJobs("RUNNING", List.of(1), List.of(1));

        JobClaim claim = repository.claimNext("worker-1", Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(claim.chapterIndex()).isEqualTo(1);
    }

    @Test
    void explicitScopeCannotClaimAnotherWaitingScopeFromTheSameBook() {
        insertBookWithJobs("RUNNING", List.of(1), List.of(1));
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=1", "scope-a");
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=2", "scope-b");
        jdbcTemplate.update("UPDATE book SET active_scope_id=? WHERE id=1", "scope-a");

        JobClaim claim = repository.claimNext(
                "worker-a", Instant.parse("2026-09-05T00:00:00Z"), "scope-a");

        assertThat(claim).isNotNull();
        assertThat(claim.jobId()).isEqualTo(1L);
        assertThat(claim.scopeId()).isEqualTo("scope-a");
        assertThat(repository.claimNext(
                "worker-a", Instant.parse("2026-09-05T00:00:01Z"), "scope-a")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_job WHERE id=2", String.class)).isEqualTo("WAITING");
    }

    @Test
    void explicitScopeCannotClaimNonActiveScopeFromTheSameBook() {
        insertBookWithJobs("RUNNING", List.of(1, 2), List.of());
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=1", "scope-a");
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=2", "scope-b");
        jdbcTemplate.update("UPDATE book SET active_scope_id=? WHERE id=1", "scope-a");

        assertThat(repository.claimNext(
                "worker-b", Instant.parse("2026-09-05T00:00:00Z"), "scope-b")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_job WHERE id=1", String.class)).isEqualTo("WAITING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_job WHERE id=2", String.class)).isEqualTo("WAITING");
    }

    @Test
    void legacyClaimProtocolStaysWithinTheBookCurrentScope() {
        insertBookWithJobs("RUNNING", List.of(1), List.of(1));
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=1", "scope-a");
        jdbcTemplate.update("UPDATE generation_job SET scope_id=? WHERE id=2", "scope-b");
        jdbcTemplate.update("UPDATE book SET active_scope_id=? WHERE id=1", "scope-a");

        JobClaim claim = repository.claimNext(
                "worker-a", Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(claim).isNotNull();
        assertThat(claim.scopeId()).isEqualTo("scope-a");
        assertThat(repository.claimNext(
                "worker-a", Instant.parse("2026-09-05T00:00:01Z"))).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_job WHERE id=2", String.class)).isEqualTo("WAITING");
    }

    private void insertBookWithJobs(String bookStatus, List<Integer> firstChapterSegments,
                                    List<Integer> laterChapterSegments) {
        jdbcTemplate.update("INSERT INTO book (title, status) VALUES ('Test', ?)", bookStatus);
        jdbcTemplate.update("INSERT INTO book_version (book_id, source_file_path, source_file_sha256, "
                        + "parser_version, segmentation_rule_version) VALUES (1, '/tmp/book.epub', ?, 'v1', 'v1')",
                String.format("%064d", 1));
        insertChapter(1, 1, firstChapterSegments);
        if (!laterChapterSegments.isEmpty()) {
            insertChapter(1, 2, laterChapterSegments);
        }
    }

    private void insertChapter(long versionId, int chapterNumber, List<Integer> segments) {
        jdbcTemplate.update("INSERT INTO chapter (book_version_id, chapter_number, title, text_path, text_sha256, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'WAITING')",
                versionId, chapterNumber, "Chapter " + chapterNumber, "/tmp/chapter.txt", String.format("%064d", chapterNumber));
        for (Integer segment : segments) {
            jdbcTemplate.update("INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, "
                            + "preset_snapshot, status) VALUES (?, ?, ?, ?, '{}'::jsonb, 'WAITING')",
                    chapterNumber, segment, "Segment " + segment, String.format("%064d", segment));
        }
    }
}
