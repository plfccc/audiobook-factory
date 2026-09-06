package com.audiobookfactory.control.job;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.audio.AudioValidationResult;
import com.audiobookfactory.control.audio.FfmpegMediaService;
import com.audiobookfactory.control.config.AppProperties;
import com.audiobookfactory.control.library.ChapterCompletionPort;
import com.audiobookfactory.control.library.LibraryPublishService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService implements ChapterCompletionPort {

    public static final String DEFAULT_MODEL = "Qwen/Qwen3-TTS-12Hz-1.7B-Base";
    public static final int DEFAULT_LEASE_SECONDS = JobClaimRepository.LEASE_SECONDS;

    private static final long MAX_RESULT_BYTES = 512L * 1024 * 1024;
    private static final String ENV_LIBRARY_ROOT = "AUDIOBOOKSHELF_LIBRARY_ROOT";
    private static final String ENV_LIBRARY_PATH = "AUDIOBOOKSHELF_LIBRARY_PATH";
    private static final Set<String> RETRYABLE_FAILURE_CODES = Set.of(
            "GENERATION_TIMEOUT", "DOWNLOAD_TIMEOUT", "PAGE_NOT_READY", "TEMPORARY_FAILURE",
            "AUDIO_INVALID");
    private static final Set<String> CONTROL_PLANE_FAILURE_CODES = Set.of(
            "AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED", "WAITING_FOR_GPU",
            "WORKER_UNAUTHORIZED");
    private static final Set<String> SENSITIVE_PRESET_KEYS = Set.of(
            "cloneprompt", "secret", "token", "password", "apikey",
            "workertoken", "enrollmenttoken", "enrolltoken", "accesstoken");
    private static final Set<String> SCOPE_METADATA_KEYS = Set.of("runid", "batchid", "scopeid");
    private static final Set<String> LEASED_STATUSES = Set.of("LEASED", "GENERATING", "UPLOADING");
    private static final List<TtsModelView> TTS_MODELS = List.of(
            new TtsModelView(
                    "qwen3-tts", DEFAULT_MODEL, "1.0", 8L * 1024 * 1024 * 1024, 500,
                    new TtsCapabilities(List.of("zh-CN", "en-US"), false, true, false, false),
                    "https://github.com/QwenLM/Qwen3-TTS/blob/main/LICENSE"),
            new TtsModelView(
                    "qwen3-tts", "Qwen/Qwen3-TTS-12Hz-0.6B-Base", "1.0",
                    4L * 1024 * 1024 * 1024, 400,
                    new TtsCapabilities(List.of("zh-CN", "en-US"), false, true, false, false),
                    "https://github.com/QwenLM/Qwen3-TTS/blob/main/LICENSE"),
            new TtsModelView(
                    "cosyvoice3", "FunAudioLLM/Fun-CosyVoice3-0.5B-2512", "3.0",
                    8L * 1024 * 1024 * 1024, 300,
                    new TtsCapabilities(List.of("zh-CN", "en-US"), false, true, true, false),
                    "https://github.com/FunAudioLLM/CosyVoice/blob/main/LICENSE"),
            new TtsModelView(
                    "indextts-2.5", "IndexTeam/IndexTTS-2.5", "2.5",
                    8L * 1024 * 1024 * 1024, 200,
                    new TtsCapabilities(List.of("zh-CN", "en-US"), false, true, true, true),
                    "https://github.com/index-tts/index-tts/blob/main/LICENSE"),
            new TtsModelView(
                    "f5-tts", "SWivid/F5-TTS", "1.0", 6L * 1024 * 1024 * 1024, 100,
                    new TtsCapabilities(List.of("zh-CN", "en-US"), false, true, false, false),
                    "https://github.com/SWivid/F5-TTS/blob/main/LICENSE"));
    private static final RowMapper<BookRow> BOOK_ROW_MAPPER = (resultSet, rowNum) -> new BookRow(
            resultSet.getLong("id"), resultSet.getString("status"),
            resultSet.getString("active_scope_id"));
    private static final RowMapper<JobRow> JOB_ROW_MAPPER = (resultSet, rowNum) -> new JobRow(
            resultSet.getLong("id"),
            resultSet.getString("status"),
            resultSet.getString("lease_owner"),
            timestamp(resultSet.getTimestamp("lease_expires_at")),
            resultSet.getLong("chapter_id"),
            resultSet.getInt("chapter_number"),
            resultSet.getLong("book_id"),
            resultSet.getLong("book_version_id"),
            resultSet.getInt("segment_index"),
            resultSet.getString("error_code"),
            resultSet.getString("error_message"),
            resultSet.getString("result_idempotency_key"),
            resultSet.getString("book_status"),
            resultSet.getString("run_id"),
            resultSet.getString("batch_id"),
            resultSet.getString("scope_id"),
            resultSet.getString("active_scope_id"));

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path libraryRoot;
    private final FfmpegMediaService mediaService;
    private final LibraryPublishService libraryPublishService;

    @Autowired
    public JobService(JdbcTemplate jdbcTemplate,
                      PlatformTransactionManager transactionManager,
                      ObjectMapper objectMapper,
                      AppProperties appProperties,
                      FfmpegMediaService mediaService,
                      LibraryPublishService libraryPublishService) {
        this(jdbcTemplate, transactionManager, objectMapper, appProperties.storageRoot(),
                mediaService, libraryPublishService);
    }

    public JobService(JdbcTemplate jdbcTemplate,
                      PlatformTransactionManager transactionManager,
                      ObjectMapper objectMapper,
                      Path storageRoot) {
        this(jdbcTemplate, transactionManager, objectMapper, storageRoot, null, null);
    }

    public JobService(JdbcTemplate jdbcTemplate,
                      PlatformTransactionManager transactionManager,
                      ObjectMapper objectMapper,
                      Path storageRoot,
                      FfmpegMediaService mediaService,
                      LibraryPublishService libraryPublishService) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot must not be null")
                .toAbsolutePath().normalize();
        this.libraryRoot = libraryRootFromEnvironment();
        this.mediaService = mediaService;
        this.libraryPublishService = libraryPublishService;
    }

    public JobBatch createPreview(long bookId, Map<String, Object> request) {
        return createJobs(bookId, request, true);
    }

    public JobBatch createGeneration(long bookId, Map<String, Object> request) {
        return createJobs(bookId, request, false);
    }

    public List<TtsModelView> listTtsModels() {
        return TTS_MODELS;
    }

    public List<TtsPresetView> listTtsPresets() {
        return jdbcTemplate.query("""
                SELECT id, engine, model, model_version, style_instruction, speed,
                       model_parameters::text AS model_parameters_json, segment_length
                FROM tts_preset
                ORDER BY id
                """, (resultSet, rowNum) -> new TtsPresetView(
                resultSet.getLong("id"),
                resultSet.getString("engine"),
                resultSet.getString("model"),
                resultSet.getString("model_version"),
                resultSet.getString("style_instruction"),
                resultSet.getBigDecimal("speed"),
                safeJsonObject(resultSet.getString("model_parameters_json")),
                resultSet.getInt("segment_length")));
    }

    private JobBatch createJobs(long bookId, Map<String, Object> request, boolean preview) {
        requirePositiveId(bookId, "bookId");
        Map<String, Object> body = request == null ? Map.of() : request;
        try {
            JobBatch result = transactionTemplate.execute(status ->
                    createJobsInTransaction(bookId, body, preview));
            if (result == null) {
                throw new IllegalStateException("job creation transaction returned no result");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException("ANOTHER_BOOK_RUNNING", 409,
                    "Only one book may be running at a time");
        }
    }

    private JobBatch createJobsInTransaction(long bookId, Map<String, Object> request, boolean preview) {
        BookRow book = findBookForUpdate(bookId).orElseThrow(() ->
                new ApiException("BOOK_NOT_FOUND", 404, "Book was not found"));
        List<ChapterRef> chapters = findChapters(bookId);
        if (chapters.isEmpty()) {
            throw new ApiException("NO_CHAPTERS", 409, "Book has no chapters");
        }

        int defaultEnd = chapters.get(chapters.size() - 1).chapterNumber();
        int start = readInt(request, 1,
                "chapterNumber", "chapterIndex", "chapterStart", "fromChapter", "startChapter");
        int end = preview
                ? start
                : readInt(request, defaultEnd, "chapterEnd", "toChapter", "endChapter");
        if (start <= 0 || end < start) {
            throw new ApiException("INVALID_CHAPTER_RANGE", 400, "Chapter range is invalid");
        }
        ScopeMetadata scope = scopeMetadata(request, book.activeScopeId());
        String presetSnapshot = presetSnapshot(request);
        Integer requestedSegment = preview
                ? optionalInt(request, "segmentIndex", "segmentNumber")
                : null;

        List<String> jobIds = new ArrayList<>();
        for (ChapterRef chapter : chapters) {
            if (chapter.chapterNumber() < start || chapter.chapterNumber() > end) {
                continue;
            }
            List<Long> chapterJobs = findChapterJobs(chapter.id(), requestedSegment);
            if (chapterJobs.isEmpty()) {
                if (requestedSegment != null) {
                    throw new ApiException("JOB_NOT_FOUND", 404, "Requested segment was not found");
                }
                continue;
            }
            for (Long jobId : chapterJobs) {
                jdbcTemplate.update("""
                        UPDATE generation_job
                        SET status = CASE WHEN status = 'SUCCESS' THEN status ELSE 'WAITING' END,
                            run_id = CASE WHEN status = 'SUCCESS' THEN run_id ELSE ? END,
                            batch_id = CASE WHEN status = 'SUCCESS' THEN batch_id ELSE ? END,
                            scope_id = CASE WHEN status = 'SUCCESS' THEN scope_id ELSE ? END,
                            preset_snapshot = CASE WHEN status = 'SUCCESS'
                                THEN preset_snapshot ELSE CAST(? AS jsonb) END,
                            lease_owner = CASE WHEN status = 'SUCCESS' THEN lease_owner ELSE NULL END,
                            lease_expires_at = CASE WHEN status = 'SUCCESS' THEN lease_expires_at ELSE NULL END,
                            heartbeat_at = CASE WHEN status = 'SUCCESS' THEN heartbeat_at ELSE NULL END,
                            error_code = CASE WHEN status = 'SUCCESS' THEN error_code ELSE NULL END,
                            error_message = CASE WHEN status = 'SUCCESS' THEN error_message ELSE NULL END,
                            next_retry_at = CASE WHEN status = 'SUCCESS' THEN next_retry_at ELSE NULL END,
                            started_at = CASE WHEN status = 'SUCCESS' THEN started_at ELSE NULL END,
                            finished_at = CASE WHEN status = 'SUCCESS' THEN finished_at ELSE NULL END,
                            result_idempotency_key = CASE WHEN status = 'SUCCESS'
                                THEN result_idempotency_key ELSE NULL END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, scope.runId(), scope.batchId(), scope.scopeId(), presetSnapshot, jobId);
                jobIds.add(Long.toString(jobId));
            }
            jdbcTemplate.update("""
                    UPDATE chapter
                    SET status = CASE WHEN status = 'SUCCESS' THEN status ELSE 'WAITING' END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, chapter.id());
        }
        if (jobIds.isEmpty()) {
            throw new ApiException("NO_JOBS", 409, "No generation jobs matched the request");
        }

        jdbcTemplate.update("UPDATE book SET status = 'RUNNING', active_scope_id = COALESCE(?, active_scope_id), "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                scope.scopeId(), book.id());
        return new JobBatch(book.id(), jobIds, start, end, preview ? "PREVIEW" : "GENERATION", "WAITING",
                scope.runId(), scope.batchId(), scope.scopeId());
    }

    public void pauseBook(long bookId) {
        changeBookStatus(bookId, Set.of("DRAFT", "RUNNING", "PAUSED"), "PAUSED", true);
    }

    public void resumeBook(long bookId) {
        changeBookStatus(bookId, Set.of("PAUSED"), "RUNNING", false);
    }

    private void changeBookStatus(long bookId, Set<String> acceptedStatuses,
                                  String targetStatus, boolean idempotentTarget) {
        requirePositiveId(bookId, "bookId");
        try {
            Integer updated = transactionTemplate.execute(status -> {
                Optional<BookRow> book = findBookForUpdate(bookId);
                if (book.isEmpty()) {
                    throw new ApiException("BOOK_NOT_FOUND", 404, "Book was not found");
                }
                if (idempotentTarget && targetStatus.equals(book.get().status())) {
                    if ("PAUSED".equals(targetStatus)) {
                        invalidateBookLeases(bookId);
                    }
                    return 0;
                }
                if (!acceptedStatuses.contains(book.get().status())) {
                    throw new ApiException("BOOK_STATE_CONFLICT", 409,
                            "Book cannot transition from its current state");
                }
                int statusUpdated = jdbcTemplate.update(
                        "UPDATE book SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        targetStatus, bookId);
                if ("PAUSED".equals(targetStatus)) {
                    invalidateBookLeases(bookId);
                }
                return statusUpdated;
            });
            if (updated == null) {
                throw new IllegalStateException("book status transaction returned no result");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException("ANOTHER_BOOK_RUNNING", 409,
                    "Only one book may be running at a time");
        }
    }

    private void invalidateBookLeases(long bookId) {
        jdbcTemplate.update("""
                UPDATE generation_job gj
                SET status = CASE
                        WHEN gj.status IN ('LEASED', 'GENERATING', 'UPLOADING') THEN 'WAITING'
                        ELSE gj.status
                    END,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE EXISTS (
                    SELECT 1
                    FROM chapter c
                    JOIN book_version bv ON bv.id = c.book_version_id
                    WHERE c.id = gj.chapter_id AND bv.book_id = ?
                )
                  AND (gj.status IN ('LEASED', 'GENERATING', 'UPLOADING')
                       OR gj.lease_owner IS NOT NULL
                       OR gj.lease_expires_at IS NOT NULL)
                """, bookId);
    }

    public void heartbeat(long jobId, String workerId) {
        requirePositiveId(jobId, "jobId");
        String owner = requireWorkerId(workerId);
        transactionTemplate.executeWithoutResult(status -> {
            JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                    new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
            ensureCurrentLease(job, owner);
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    UPDATE generation_job
                    SET status = CASE WHEN status = 'LEASED' THEN 'GENERATING' ELSE status END,
                        lease_expires_at = ?::timestamptz + interval '5 minutes',
                        heartbeat_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), jobId);
            jdbcTemplate.update("""
                    UPDATE worker_registration
                    SET last_heartbeat_at = ?, updated_at = ?
                    WHERE worker_id = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), Timestamp.from(now), owner);
        });
    }

    public void recordFailure(long jobId, String workerId, String code, String message) {
        requirePositiveId(jobId, "jobId");
        String owner = requireWorkerId(workerId);
        String normalizedCode = normalizeFailureCode(code);
        String normalizedMessage = normalizeFailureMessage(message);
        transactionTemplate.executeWithoutResult(status -> {
            JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                    new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
            if ("SUCCESS".equals(job.status())) {
                throw new ApiException("JOB_ALREADY_COMPLETED", 409, "Job has already completed");
            }
            boolean sameReportedFailure = normalizedCode.equals(job.errorCode())
                    && normalizedMessage.equals(job.errorMessage())
                    && ("FAILED".equals(job.status()) || "WAITING".equals(job.status()));
            if (sameReportedFailure) {
                ensureFailureIdempotencyLease(job, owner);
                return;
            }
            ensureCurrentLease(job, owner);
            Instant now = Instant.now();
            boolean controlPlaneFailure = CONTROL_PLANE_FAILURE_CODES.contains(normalizedCode);
            boolean retryable = !controlPlaneFailure && RETRYABLE_FAILURE_CODES.contains(normalizedCode);
            Timestamp nextRetryAt = retryable
                    ? Timestamp.from(now.plusSeconds(retryDelaySeconds(1))) : null;
            String targetStatus = retryable ? "WAITING" : "FAILED";
            if (controlPlaneFailure) {
                targetStatus = "WAITING";
            }
            String retainedLeaseOwner = controlPlaneFailure ? null : owner;
            Timestamp retainedLeaseExpiresAt = controlPlaneFailure || job.leaseExpiresAt() == null
                    ? null : Timestamp.from(job.leaseExpiresAt());
            jdbcTemplate.update("""
                    UPDATE generation_job
                    SET status = ?,
                        lease_owner = ?,
                        lease_expires_at = ?,
                        heartbeat_at = NULL,
                        error_code = ?,
                        error_message = ?,
                        next_retry_at = ?,
                        finished_at = CASE WHEN ? = 'FAILED' THEN ? ELSE finished_at END,
                        updated_at = ?
                        WHERE id = ?
                    """, targetStatus, retainedLeaseOwner, retainedLeaseExpiresAt,
                    normalizedCode, normalizedMessage, nextRetryAt, targetStatus,
                    Timestamp.from(now), Timestamp.from(now), jobId);
            jdbcTemplate.update("""
                    UPDATE chapter
                    SET status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status <> 'SUCCESS'
                    """, controlPlaneFailure || retryable ? "WAITING" : "FAILED", job.chapterId());
            if (controlPlaneFailure) {
                jdbcTemplate.update("""
                        UPDATE book
                        SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'RUNNING'
                        """, job.bookId());
                invalidateBookLeases(job.bookId());
            }
        });
    }

    public void recordResult(long jobId, String workerId, MultipartFile audio, String metadataJson) {
        requirePositiveId(jobId, "jobId");
        String owner = requireWorkerId(workerId);
        if (audio == null || audio.isEmpty()) {
            throw new ApiException("INVALID_RESULT", 400, "Result audio must not be empty");
        }
        ParsedResultMetadata declaredMetadata = parseResultMetadata(metadataJson);
        StagedResult staged = stageResult(audio);
        ResultTarget target = new ResultTarget();
        try {
            verifyResultLease(jobId, owner);
            ParsedResultMetadata metadata = validateResultMetadata(
                    declaredMetadata, staged.sha256(), staged.sizeBytes());
            AudioValidationResult validation = validateUploadedAudio(staged);
            if (!validation.valid()) {
                markResultRetryable(jobId, owner, validation.errorCode());
                throw invalidResult("Result audio is invalid");
            }
            JobRow job = transactionTemplate.execute(status ->
                    persistResult(jobId, owner, staged.path(), metadata, validation, target));
            if (job == null) {
                throw new IllegalStateException("result transaction returned no job");
            }
            publishReadyChapter(job, owner);
        } finally {
            deleteQuietly(staged.path());
            if (target.moved() && !target.committed()) {
                deleteQuietly(target.finalPath());
            }
        }
    }

    @Override
    public void completeChapter(long chapterId, String finalAudioPath) {
        completeChapterAfterMerge(chapterId, finalAudioPath);
    }

    public void completeChapterAfterMerge(long chapterId, String finalAudioPath) {
        requirePositiveId(chapterId, "chapterId");
        Path finalPath = safeChapterAudioPath(finalAudioPath);
        if (!Files.isRegularFile(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException("CHAPTER_AUDIO_NOT_FOUND", 409, "Merged chapter audio is not ready");
        }
        transactionTemplate.executeWithoutResult(status -> {
            Boolean chapterExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM chapter WHERE id = ?)", Boolean.class, chapterId);
            if (!Boolean.TRUE.equals(chapterExists)) {
                throw new ApiException("CHAPTER_NOT_FOUND", 404, "Chapter was not found");
            }
            Boolean allSuccessful = jdbcTemplate.queryForObject("""
                    SELECT NOT EXISTS (
                        SELECT 1 FROM generation_job
                        WHERE chapter_id = ? AND status <> 'SUCCESS'
                    )
                    """, Boolean.class, chapterId);
            if (!Boolean.TRUE.equals(allSuccessful)) {
                throw new ApiException("CHAPTER_NOT_READY", 409, "Chapter still has unfinished jobs");
            }
            jdbcTemplate.update("""
                    UPDATE chapter
                    SET status = 'SUCCESS', final_audio_path = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, finalPath.toString(), chapterId);
        });
    }

    public JsonNode workerPreset(String presetSnapshot) {
        if (presetSnapshot == null || presetSnapshot.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(presetSnapshot);
            if (parsed == null || !parsed.isObject()) {
                return objectMapper.createObjectNode();
            }
            return sanitizeJson(parsed);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private void verifyResultLease(long jobId, String workerId) {
        transactionTemplate.executeWithoutResult(status -> {
            JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                    new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
            if ("SUCCESS".equals(job.status())) {
                ensureCompletedResultLease(job, workerId);
            } else {
                ensureCurrentLease(job, workerId);
            }
        });
    }

    private AudioValidationResult validateUploadedAudio(StagedResult staged) {
        if (mediaService == null) {
            throw new ApiException("MEDIA_VALIDATION_UNAVAILABLE", 503,
                    "Audio validation is not configured");
        }
        AudioValidationResult validation;
        try {
            validation = mediaService.validate(staged.path());
        } catch (RuntimeException exception) {
            validation = AudioValidationResult.invalid(FfmpegMediaService.AUDIO_INVALID,
                    "Audio validation failed");
        }
        if (validation == null || !validation.valid()
                || validation.sizeBytes() != staged.sizeBytes()
                || validation.sha256() == null
                || !validation.sha256().equalsIgnoreCase(staged.sha256())) {
            return AudioValidationResult.invalid(
                    validation == null ? FfmpegMediaService.AUDIO_INVALID : validation.errorCode(),
                    "Result audio is invalid");
        }
        return validation;
    }

    private void markResultRetryable(long jobId, String workerId, String errorCode) {
        transactionTemplate.executeWithoutResult(status -> {
            JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                    new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
            if ("SUCCESS".equals(job.status())) {
                ensureCompletedResultLease(job, workerId);
                return;
            }
            ensureCurrentLease(job, workerId);
            markJobWaiting(job.id(), job.chapterId(), retryableCode(errorCode), "Result audio is invalid");
        });
    }

    private void publishReadyChapter(JobRow job, String workerId) {
        if (libraryPublishService == null) {
            return;
        }
        Optional<ChapterPublishData> ready = findReadyChapter(job.chapterId());
        if (ready.isEmpty()) {
            return;
        }
        try {
            Path published = libraryPublishService.publishChapter(
                    ready.get().chapter(), ready.get().assets());
            if (published == null) {
                throw new LibraryPublishService.PublishException(
                        "CHAPTER_PUBLISH_FAILED", "Chapter publish returned no path");
            }
        } catch (RuntimeException exception) {
            markPublishRetry(job.id(), workerId, publishFailureCode(exception));
            throw publishFailure(exception);
        }
    }

    private Optional<ChapterPublishData> findReadyChapter(long chapterId) {
        List<ChapterPublishRow> chapters = jdbcTemplate.query("""
                SELECT c.id AS chapter_id, c.chapter_number, c.title AS chapter_title,
                       b.title AS book_title, c.status,
                       COUNT(gj.id) AS total_jobs,
                       COUNT(gj.id) FILTER (WHERE gj.status = 'SUCCESS') AS successful_jobs
                FROM chapter c
                JOIN book_version bv ON bv.id = c.book_version_id
                JOIN book b ON b.id = bv.book_id
                LEFT JOIN generation_job gj ON gj.chapter_id = c.id
                WHERE c.id = ?
                GROUP BY c.id, c.chapter_number, c.title, b.title, c.status
                """, (resultSet, rowNum) -> new ChapterPublishRow(
                resultSet.getLong("chapter_id"),
                resultSet.getInt("chapter_number"),
                resultSet.getString("chapter_title"),
                resultSet.getString("book_title"),
                resultSet.getString("status"),
                resultSet.getLong("total_jobs"),
                resultSet.getLong("successful_jobs")), chapterId);
        if (chapters.isEmpty()) {
            return Optional.empty();
        }
        ChapterPublishRow chapter = chapters.get(0);
        if ("SUCCESS".equals(chapter.status()) || chapter.totalJobs() == 0
                || chapter.totalJobs() != chapter.successfulJobs()) {
            return Optional.empty();
        }
        List<LibraryPublishService.AudioAsset> assets = jdbcTemplate.query("""
                SELECT gj.segment_index, aa.file_path
                FROM generation_job gj
                JOIN audio_asset aa ON aa.job_id = gj.id
                WHERE gj.chapter_id = ? AND gj.status = 'SUCCESS'
                ORDER BY gj.segment_index, gj.id
                """, (resultSet, rowNum) -> new LibraryPublishService.AudioAsset(
                resultSet.getInt("segment_index"), Path.of(resultSet.getString("file_path"))), chapterId);
        if (assets.size() != chapter.totalJobs()) {
            return Optional.empty();
        }
        return Optional.of(new ChapterPublishData(
                new LibraryPublishService.Chapter(chapter.chapterId(), chapter.bookTitle(),
                        chapter.chapterNumber(), chapter.chapterTitle()), assets));
    }

    private void markPublishRetry(long jobId, String workerId, String errorCode) {
        transactionTemplate.executeWithoutResult(status -> {
            JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                    new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
            if ("SUCCESS".equals(job.status())) {
                ensureCompletedResultLease(job, workerId);
            } else {
                ensureCurrentLease(job, workerId);
            }
            markJobWaiting(job.id(), job.chapterId(), retryableCode(errorCode),
                    "Chapter publish is retryable");
        });
    }

    private void markJobWaiting(long jobId, long chapterId, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE generation_job
                SET status = 'WAITING', lease_owner = NULL, lease_expires_at = NULL,
                    heartbeat_at = NULL, error_code = ?, error_message = ?,
                    next_retry_at = ?, finished_at = NULL, updated_at = ?
                WHERE id = ?
                """, errorCode, errorMessage, Timestamp.from(now.plusSeconds(retryDelaySeconds(1))),
                Timestamp.from(now), jobId);
        jdbcTemplate.update("""
                UPDATE chapter
                SET status = 'WAITING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status <> 'SUCCESS'
                """, chapterId);
    }

    private String publishFailureCode(RuntimeException exception) {
        if (exception instanceof LibraryPublishService.PublishException publishException) {
            return publishException.code();
        }
        if (exception instanceof FfmpegMediaService.MediaPipelineException mediaException) {
            return mediaException.code();
        }
        return "TEMPORARY_FAILURE";
    }

    private ApiException publishFailure(RuntimeException exception) {
        String code = publishFailureCode(exception);
        return new ApiException(code, 503, "Chapter publish is not complete");
    }

    private String retryableCode(String code) {
        return code != null && RETRYABLE_FAILURE_CODES.contains(code)
                ? code : "TEMPORARY_FAILURE";
    }

    private ApiException invalidResult(String message) {
        return new ApiException("INVALID_RESULT", 400, message);
    }

    private JobRow persistResult(long jobId, String workerId, Path stagedPath,
                                 ParsedResultMetadata metadata, AudioValidationResult validation,
                                 ResultTarget target) {
        JobRow job = findJobForUpdate(jobId).orElseThrow(() ->
                new ApiException("JOB_NOT_FOUND", 404, "Job was not found"));
        if ("SUCCESS".equals(job.status())) {
            ensureCompletedResultLease(job, workerId);
            if (metadata.idempotencyKey().equals(job.resultIdempotencyKey())) {
                return job;
            }
            throw new ApiException("RESULT_IDEMPOTENCY_CONFLICT", 409,
                    "A different result was already recorded for this job");
        }
        ensureCurrentLease(job, workerId);
        Path finalPath = resultPath(job);
        try {
            Files.createDirectories(finalPath.getParent());
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(finalPath)
                        || !Files.isRegularFile(finalPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(finalPath) != validation.sizeBytes()
                        || !validation.sha256().equalsIgnoreCase(sha256(finalPath))) {
                    throw new IOException("An existing result does not match the validated asset");
                }
                Files.deleteIfExists(stagedPath);
            } else {
                moveWithoutOverwrite(stagedPath, finalPath);
                target.moved(finalPath);
            }
        } catch (IOException exception) {
            throw new ApiException("RESULT_STORAGE_FAILED", 500, "Unable to store result audio");
        }

        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE generation_job
                SET status = 'SUCCESS',
                    lease_owner = ?,
                    lease_expires_at = ?,
                    heartbeat_at = NULL,
                    error_code = NULL,
                    error_message = NULL,
                    next_retry_at = NULL,
                    finished_at = ?,
                    result_idempotency_key = ?,
                    updated_at = ?
                WHERE id = ?
                """, workerId,
                job.leaseExpiresAt() == null ? null : Timestamp.from(job.leaseExpiresAt()),
                Timestamp.from(now), metadata.idempotencyKey(), Timestamp.from(now), jobId);
        if (updated != 1) {
            throw new ApiException("RESULT_CONFLICT", 409, "Job result could not be recorded");
        }
        jdbcTemplate.update("""
                INSERT INTO audio_asset (job_id, file_path, format, duration_ms, sample_rate,
                                         channels, size_bytes, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id, file_path) DO UPDATE SET
                    format = EXCLUDED.format,
                    duration_ms = EXCLUDED.duration_ms,
                    sample_rate = EXCLUDED.sample_rate,
                    channels = EXCLUDED.channels,
                    size_bytes = EXCLUDED.size_bytes,
                    sha256 = EXCLUDED.sha256
                """, jobId, finalPath.toString(), metadata.format(), validation.durationMs(),
                validation.sampleRate(), validation.channels(), validation.sizeBytes(), validation.sha256());
        target.markCommitted();
        return job;
    }

    private StagedResult stageResult(MultipartFile audio) {
        Path directory = storageRoot.resolve(".staging").resolve("results").normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new ApiException("INVALID_RESULT", 400, "Result storage path is invalid");
        }
        Path target = directory.resolve(UUID.randomUUID() + ".upload");
        MessageDigest digest = sha256Digest();
        long size = 0;
        try {
            Files.createDirectories(directory);
            try (InputStream input = audio.getInputStream();
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_RESULT_BYTES) {
                        throw new ApiException("INVALID_RESULT", 400, "Result audio is too large");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            return new StagedResult(target, HexFormat.of().formatHex(digest.digest()), size);
        } catch (ApiException exception) {
            deleteQuietly(target);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(target);
            throw new ApiException("INVALID_RESULT", 400, "Unable to read result audio");
        }
    }

    private ParsedResultMetadata parseResultMetadata(String metadataJson) {
        JsonNode metadata;
        try {
            metadata = metadataJson == null || metadataJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(metadataJson);
        } catch (JsonProcessingException exception) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        if (metadata == null || !metadata.isObject()) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        String declaredSha256 = declaredSha256(metadata);
        Long declaredSizeBytes = longValue(metadata, "sizeBytes", "size_bytes");
        if (declaredSizeBytes != null && declaredSizeBytes < 0) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        String format = text(metadata, "format", "outputFormat", "output_format");
        format = format == null ? "wav" : format.toLowerCase(Locale.ROOT);
        if (!format.matches("[a-z0-9]{1,16}")) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        Long durationMs = durationMilliseconds(metadata);
        Integer sampleRate = intValue(metadata, "sampleRate", "sample_rate");
        Integer channels = intValue(metadata, "channels");
        if ((durationMs != null && durationMs < 0)
                || (sampleRate != null && sampleRate <= 0)
                || (channels != null && channels <= 0)) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        String idempotencyKey = text(metadata, "idempotencyKey", "idempotency_key");
        if (idempotencyKey != null && idempotencyKey.length() > 256) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        return new ParsedResultMetadata(declaredSha256, declaredSizeBytes, format, durationMs,
                sampleRate, channels, idempotencyKey, null, null);
    }

    private String declaredSha256(JsonNode metadata) {
        JsonNode value = metadata.get("sha256");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || !value.textValue().matches("[0-9a-fA-F]{64}")) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        return value.textValue().toLowerCase(Locale.ROOT);
    }

    private Long durationMilliseconds(JsonNode metadata) {
        Long durationMs = longValue(metadata, "durationMs", "duration_ms");
        if (durationMs != null && durationMs < 0) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }

        BigDecimal durationSeconds = decimalValue(metadata, "durationSeconds", "duration_seconds");
        Long secondsAsMilliseconds = null;
        if (durationSeconds != null) {
            if (durationSeconds.signum() < 0) {
                throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
            }
            try {
                secondsAsMilliseconds = durationSeconds.multiply(BigDecimal.valueOf(1000))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
            } catch (ArithmeticException exception) {
                throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
            }
        }
        if (durationMs != null && secondsAsMilliseconds != null
                && !durationMs.equals(secondsAsMilliseconds)) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        return secondsAsMilliseconds == null ? durationMs : secondsAsMilliseconds;
    }

    private static BigDecimal decimalValue(JsonNode node, String... names) {
        JsonNode value = null;
        for (String name : names) {
            JsonNode candidate = node.get(name);
            if (candidate != null && !candidate.isNull()) {
                value = candidate;
                break;
            }
        }
        if (value == null) {
            return null;
        }
        if (!value.isNumber() && !value.isTextual()) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
    }

    private ParsedResultMetadata validateResultMetadata(ParsedResultMetadata metadata,
                                                          String actualSha256, long actualSize) {
        if (metadata.declaredSha256() != null
                && metadata.declaredSha256().matches("[0-9a-fA-F]{64}")
                && !metadata.declaredSha256().equalsIgnoreCase(actualSha256)) {
            throw new ApiException("INVALID_RESULT", 400, "Result SHA-256 does not match audio");
        }
        if (metadata.declaredSizeBytes() != null && metadata.declaredSizeBytes() != actualSize) {
            throw new ApiException("INVALID_RESULT", 400, "Result size does not match audio");
        }
        String idempotencyKey = metadata.idempotencyKey();
        if (idempotencyKey == null) {
            idempotencyKey = (metadata.declaredSha256() == null
                    ? actualSha256 : metadata.declaredSha256()) + ":" + actualSize;
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 256) {
            throw new ApiException("INVALID_RESULT", 400, "Result idempotency key is invalid");
        }
        return metadata.withActual(actualSha256, actualSize).withIdempotencyKey(idempotencyKey);
    }

    private Optional<BookRow> findBookForUpdate(long bookId) {
        List<BookRow> books = jdbcTemplate.query(
                "SELECT id, status, active_scope_id FROM book WHERE id = ? FOR UPDATE",
                BOOK_ROW_MAPPER, bookId);
        return books.stream().findFirst();
    }

    private List<ChapterRef> findChapters(long bookId) {
        return jdbcTemplate.query("""
                SELECT c.id, c.chapter_number
                FROM chapter c
                JOIN book_version bv ON bv.id = c.book_version_id
                WHERE bv.book_id = ?
                  AND bv.id = (
                      SELECT latest.id FROM book_version latest
                      WHERE latest.book_id = bv.book_id
                      ORDER BY latest.id DESC LIMIT 1
                  )
                ORDER BY c.chapter_number
                """, (resultSet, rowNum) -> new ChapterRef(
                resultSet.getLong("id"), resultSet.getInt("chapter_number")), bookId);
    }

    private List<Long> findChapterJobs(long chapterId, Integer segmentIndex) {
        if (segmentIndex == null) {
            return jdbcTemplate.query("""
                    SELECT id FROM generation_job
                    WHERE chapter_id = ?
                    ORDER BY segment_index, id
                    """, (resultSet, rowNum) -> resultSet.getLong("id"), chapterId);
        }
        return jdbcTemplate.query("""
                SELECT id FROM generation_job
                WHERE chapter_id = ? AND segment_index = ?
                ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getLong("id"), chapterId, segmentIndex);
    }

    private Optional<JobRow> findJobForUpdate(long jobId) {
        List<JobRow> jobs = jdbcTemplate.query("""
                SELECT gj.id, gj.status, gj.lease_owner, gj.lease_expires_at,
                       gj.chapter_id, c.chapter_number, bv.book_id, bv.id AS book_version_id,
                       gj.segment_index, gj.error_code, gj.error_message, gj.result_idempotency_key,
                       b.status AS book_status, gj.run_id, gj.batch_id, gj.scope_id,
                       b.active_scope_id
                FROM generation_job gj
                JOIN chapter c ON c.id = gj.chapter_id
                JOIN book_version bv ON bv.id = c.book_version_id
                JOIN book b ON b.id = bv.book_id
                WHERE gj.id = ?
                FOR UPDATE OF gj, b
                """, JOB_ROW_MAPPER, jobId);
        return jobs.stream().findFirst();
    }

    private Path resultPath(JobRow job) {
        Path path = storageRoot.resolve("books")
                .resolve(Long.toString(job.bookId()))
                .resolve("versions")
                .resolve(Long.toString(job.bookVersionId()))
                .resolve("chapters")
                .resolve(Integer.toString(job.chapterNumber()))
                .resolve("segments")
                .resolve(Integer.toString(job.segmentIndex()) + ".wav")
                .normalize();
        if (!path.startsWith(storageRoot)) {
            throw new ApiException("RESULT_STORAGE_FAILED", 500, "Result storage path is invalid");
        }
        return path;
    }

    private void ensureCurrentLease(JobRow job, String workerId) {
        if (!"RUNNING".equals(job.bookStatus())
                || !workerId.equals(job.leaseOwner())
                || !isCurrentScope(job)
                || !LEASED_STATUSES.contains(job.status())
                || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(Instant.now())) {
            throw leaseLost();
        }
    }

    private void ensureCompletedResultLease(JobRow job, String workerId) {
        if (!"RUNNING".equals(job.bookStatus())
                || !workerId.equals(job.leaseOwner())
                || !isCurrentScope(job)
                || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(Instant.now())) {
            throw leaseLost();
        }
    }

    private void ensureFailureIdempotencyLease(JobRow job, String workerId) {
        if (!"RUNNING".equals(job.bookStatus())
                || !workerId.equals(job.leaseOwner())
                || !isCurrentScope(job)
                || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(Instant.now())) {
            throw leaseLost();
        }
    }

    private ApiException leaseLost() {
        return new ApiException("LEASE_LOST", 409, "Worker lease is no longer valid");
    }

    private boolean isCurrentScope(JobRow job) {
        return job.scopeId() == null || Objects.equals(job.scopeId(), job.activeScopeId());
    }

    private ScopeMetadata scopeMetadata(Map<String, Object> request, String activeScopeId) {
        Object preset = request.get("preset");
        Map<?, ?> presetMap = preset instanceof Map<?, ?> ? (Map<?, ?>) preset : Map.of();
        String requestedScopeId = scopeValue(firstPresent(presetMap, request, "scopeId", "scope_id"));
        String effectiveScopeId = requestedScopeId == null
                ? scopeValue(activeScopeId) : requestedScopeId;
        if (effectiveScopeId == null) {
            effectiveScopeId = "scope-" + UUID.randomUUID();
        }
        return new ScopeMetadata(
                scopeValue(firstPresent(presetMap, request, "runId", "run_id")),
                scopeValue(firstPresent(presetMap, request, "batchId", "batch_id")),
                effectiveScopeId);
    }

    private Object firstPresent(Map<?, ?> preferred, Map<String, Object> fallback, String... keys) {
        for (String key : keys) {
            if (preferred.containsKey(key) && preferred.get(key) != null) {
                return preferred.get(key);
            }
            if (fallback.containsKey(key) && fallback.get(key) != null) {
                return fallback.get(key);
            }
        }
        return null;
    }

    private String scopeValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 128) {
            throw new ApiException("INVALID_REQUEST", 400, "Scope metadata is invalid");
        }
        return normalized;
    }

    private String presetSnapshot(Map<String, Object> request) {
        Object preset = request.get("preset");
        if (preset != null && !(preset instanceof Map<?, ?>)) {
            throw new ApiException("INVALID_PRESET", 400, "Preset must be an object");
        }
        Object source = preset == null ? request : preset;
        try {
            JsonNode node = objectMapper.valueToTree(source);
            JsonNode sanitized = sanitizeJson(node);
            if (!sanitized.isObject()) {
                throw new ApiException("INVALID_PRESET", 400, "Preset must be an object");
            }
            return objectMapper.writeValueAsString(sanitized);
        } catch (ApiException exception) {
            throw exception;
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new ApiException("INVALID_PRESET", 400, "Preset is invalid");
        }
    }

    private JsonNode sanitizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode clean = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedKey = normalizeKey(field.getKey());
                if (SENSITIVE_PRESET_KEYS.contains(normalizedKey)
                        || SCOPE_METADATA_KEYS.contains(normalizedKey)) {
                    continue;
                }
                clean.set(field.getKey(), sanitizeJson(field.getValue()));
            }
            return clean;
        }
        if (node.isArray()) {
            ArrayNode clean = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                clean.add(sanitizeJson(child));
            }
            return clean;
        }
        return node.deepCopy();
    }

    private JsonNode safeJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                return objectMapper.createObjectNode();
            }
            JsonNode sanitized = sanitizeJson(parsed);
            return sanitized.isObject() ? sanitized : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String normalizeFailureCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new ApiException("INVALID_FAILURE", 400, "Failure code is invalid");
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        if (!FailureSanitizer.isAllowedCode(normalized)) {
            throw new ApiException("INVALID_FAILURE", 400, "Failure code is invalid");
        }
        return normalized;
    }

    private String normalizeFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new ApiException("INVALID_FAILURE", 400, "Failure message is invalid");
        }
        String normalized = message.trim();
        return FailureSanitizer.sanitizeSummary(normalized);
    }

    private int readInt(Map<String, Object> request, int defaultValue, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) {
                return parseInt(request.get(key));
            }
        }
        return defaultValue;
    }

    private Integer optionalInt(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) {
                int value = parseInt(request.get(key));
                if (value <= 0) {
                    throw new ApiException("INVALID_REQUEST", 400, "Request value is invalid");
                }
                return value;
            }
        }
        return null;
    }

    private int parseInt(Object value) {
        try {
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).intValueExact();
            }
            return Integer.parseInt(String.valueOf(value));
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ApiException("INVALID_REQUEST", 400, "Request value is invalid");
        }
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new ApiException("WORKER_UNAUTHORIZED", 401, "Worker authorization is required");
        }
        return workerId.trim();
    }

    private void requirePositiveId(long id, String name) {
        if (id <= 0) {
            throw new ApiException("INVALID_ID", 400, name + " must be positive");
        }
    }

    private long retryDelaySeconds(int attempts) {
        return 15L * (1L << Math.min(Math.max(attempts - 1, 0), 2));
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
    }

    private static Integer intValue(JsonNode node, String... names) {
        Long value = longValue(node, names);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new ApiException("INVALID_RESULT", 400, "Result metadata is invalid");
        }
        return value.intValue();
    }

    private Path safeChapterAudioPath(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException("INVALID_PATH", 400, "Storage path is invalid");
        }
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (RuntimeException exception) {
            throw new ApiException("INVALID_PATH", 400, "Storage path is invalid");
        }
        Path normalized = candidate.isAbsolute()
                ? candidate.normalize() : storageRoot.resolve(candidate).normalize();
        if (!normalized.startsWith(storageRoot) && !normalized.startsWith(libraryRoot)) {
            throw new ApiException("INVALID_PATH", 400, "Storage path is invalid");
        }
        if (containsSymbolicLink(normalized)) {
            throw new ApiException("INVALID_PATH", 400, "Storage path is invalid");
        }
        return normalized;
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
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

    private boolean containsSymbolicLink(Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Path current = absolute.getRoot();
            if (current == null) {
                return false;
            }
            for (Path component : absolute) {
                current = current.resolve(component);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(current)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static Path libraryRootFromEnvironment() {
        String configured = firstNonBlank(System.getenv(ENV_LIBRARY_ROOT),
                System.getenv(ENV_LIBRARY_PATH));
        return Path.of(configured == null ? "./library" : configured)
                .toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不覆盖原始业务错误。
        }
    }

    private record BookRow(long id, String status, String activeScopeId) {
    }

    private record ChapterRef(long id, int chapterNumber) {
    }

    private record JobRow(long id, String status, String leaseOwner, Instant leaseExpiresAt,
                          long chapterId, int chapterNumber, long bookId, long bookVersionId,
                          int segmentIndex, String errorCode, String errorMessage,
                          String resultIdempotencyKey, String bookStatus, String runId,
                          String batchId, String scopeId, String activeScopeId) {
    }

    private record ChapterPublishRow(long chapterId, int chapterNumber, String chapterTitle,
                                     String bookTitle, String status, long totalJobs,
                                     long successfulJobs) {
    }

    private record ChapterPublishData(LibraryPublishService.Chapter chapter,
                                      List<LibraryPublishService.AudioAsset> assets) {
    }

    private record StagedResult(Path path, String sha256, long sizeBytes) {
    }

    private record ParsedResultMetadata(String declaredSha256, Long declaredSizeBytes,
                                        String format, Long durationMs, Integer sampleRate,
                                        Integer channels, String idempotencyKey,
                                        String actualSha256, Long actualSizeBytes) {

        private ParsedResultMetadata withActual(String sha256, long sizeBytes) {
            return new ParsedResultMetadata(declaredSha256, declaredSizeBytes, format, durationMs,
                    sampleRate, channels, idempotencyKey, sha256, sizeBytes);
        }

        private ParsedResultMetadata withIdempotencyKey(String key) {
            return new ParsedResultMetadata(declaredSha256, declaredSizeBytes, format, durationMs,
                    sampleRate, channels, key, actualSha256, actualSizeBytes);
        }
    }

    private static final class ResultTarget {

        private Path finalPath;
        private boolean moved;
        private boolean committed;

        private void moved(Path finalPath) {
            this.finalPath = finalPath;
            this.moved = true;
        }

        private void markCommitted() {
            this.committed = true;
        }

        private Path finalPath() {
            return finalPath;
        }

        private boolean moved() {
            return moved;
        }

        private boolean committed() {
            return committed;
        }
    }

    public record JobBatch(long bookId, List<String> jobIds, int chapterStart, int chapterEnd,
                           String type, String status, String runId, String batchId, String scopeId) {

        public JobBatch(long bookId, List<String> jobIds, int chapterStart, int chapterEnd,
                        String type, String status) {
            this(bookId, jobIds, chapterStart, chapterEnd, type, status, null, null, null);
        }
    }

    private record ScopeMetadata(String runId, String batchId, String scopeId) {
    }

    public record TtsModelView(
            String engineId,
            String modelId,
            String modelVersion,
            long minimumVramBytes,
            int priority,
            TtsCapabilities capabilities,
            String licenseUrl) {
    }

    public record TtsCapabilities(
            List<String> languages,
            boolean voiceDesign,
            boolean voiceClone,
            boolean emotionControl,
            boolean durationControl) {
    }

    public record TtsPresetView(
            long id,
            String engine,
            String model,
            String modelVersion,
            String styleInstruction,
            BigDecimal speed,
            JsonNode modelParameters,
            int segmentLength) {
    }
}
