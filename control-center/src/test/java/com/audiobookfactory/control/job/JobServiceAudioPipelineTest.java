package com.audiobookfactory.control.job;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.audio.AudioValidationResult;
import com.audiobookfactory.control.audio.FfmpegMediaService;
import com.audiobookfactory.control.library.LibraryPublishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceAudioPipelineTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    TransactionStatus transactionStatus;

    @Mock
    FfmpegMediaService mediaService;

    @Mock
    LibraryPublishService libraryPublishService;

    private JobService service;
    private boolean chapterReady;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        stubJobRow();
        service = new JobService(jdbcTemplate, transactionManager, new ObjectMapper(),
                Path.of("target", "job-service-audio-pipeline-test"),
                mediaService, libraryPublishService);
    }

    @Test
    void invalidMediaResultCannotMarkGenerationJobSuccessfulOrPublishChapter() throws Exception {
        byte[] audio = new byte[2048];
        when(mediaService.validate(any(Path.class)))
                .thenReturn(AudioValidationResult.invalid(FfmpegMediaService.AUDIO_INVALID, "bad audio"));

        assertThatThrownBy(() -> service.recordResult(
                1, "worker-1", new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                "{\"sha256\":\"" + sha256(audio) + "\",\"sizeBytes\":2048}"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("INVALID_RESULT"));

        verify(mediaService).validate(any(Path.class));
        verify(libraryPublishService, never()).publishChapter(any(), any());
        verify(jdbcTemplate, never()).update(contains("SET status = 'SUCCESS'"), any(Object[].class));
    }

    @Test
    void validatedResultPublishesAndCompletesOnlyWhenEverySegmentIsSuccessful() throws Exception {
        byte[] audio = new byte[2048];
        String sha256 = sha256(audio);
        Path published = Path.of("target", "job-service-audio-pipeline-test", "library", "001.mp3")
                .toAbsolutePath().normalize();
        when(mediaService.validate(any(Path.class))).thenReturn(
                AudioValidationResult.valid(2048, 1.25, "pcm_s16le", 24_000, 1, sha256));
        when(libraryPublishService.publishChapter(any(), any())).thenReturn(published);
        chapterReady = true;

        service.recordResult(1, "worker-1",
                new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                "{\"sha256\":\"" + sha256 + "\",\"sizeBytes\":2048}");

        verify(mediaService).validate(any(Path.class));
        verify(libraryPublishService).publishChapter(any(), any());
    }

    @Test
    void validatedResultDoesNotPublishOrCompleteWhileAnotherSegmentIsPending() throws Exception {
        byte[] audio = new byte[2048];
        String sha256 = sha256(audio);
        when(mediaService.validate(any(Path.class))).thenReturn(
                AudioValidationResult.valid(2048, 1.25, "pcm_s16le", 24_000, 1, sha256));
        chapterReady = false;

        service.recordResult(1, "worker-1",
                new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                "{\"sha256\":\"" + sha256 + "\",\"sizeBytes\":2048}");

        verify(libraryPublishService, never()).publishChapter(any(), any());
    }

    private void stubJobRow() throws Exception {
        ResultSet jobRow = org.mockito.Mockito.mock(ResultSet.class);
        lenient().when(jobRow.getLong(anyString())).thenAnswer(invocation -> switch (
                invocation.getArgument(0, String.class)) {
            case "id" -> 1L;
            case "chapter_id" -> 2L;
            case "book_id" -> 3L;
            case "book_version_id" -> 4L;
            default -> 0L;
        });
        lenient().when(jobRow.getInt(anyString())).thenAnswer(invocation -> switch (
                invocation.getArgument(0, String.class)) {
            case "chapter_number", "segment_index" -> 1;
            default -> 0;
        });
        lenient().when(jobRow.getString(anyString())).thenAnswer(invocation -> switch (
                invocation.getArgument(0, String.class)) {
            case "status" -> "LEASED";
            case "lease_owner" -> "worker-1";
            case "book_status" -> "RUNNING";
            default -> null;
        });
        lenient().when(jobRow.getTimestamp("lease_expires_at"))
                .thenReturn(Timestamp.from(Instant.now().plusSeconds(60)));
        ResultSet chapterRow = org.mockito.Mockito.mock(ResultSet.class);
        lenient().when(chapterRow.getLong("chapter_id")).thenReturn(2L);
        lenient().when(chapterRow.getInt("chapter_number")).thenReturn(1);
        lenient().when(chapterRow.getString("chapter_title")).thenReturn("第一章");
        lenient().when(chapterRow.getString("book_title")).thenReturn("测试书");
        lenient().when(chapterRow.getLong("total_jobs")).thenReturn(2L);
        lenient().when(chapterRow.getLong("successful_jobs")).thenAnswer(invocation -> chapterReady ? 2L : 1L);
        List<ResultSet> assetRows = new ArrayList<>();
        for (int segment = 1; segment <= 2; segment++) {
            ResultSet assetRow = org.mockito.Mockito.mock(ResultSet.class);
            lenient().when(assetRow.getInt("segment_index")).thenReturn(segment);
            lenient().when(assetRow.getString("file_path"))
                    .thenReturn("books/3/versions/4/chapters/1/segments/" + segment + ".wav");
            assetRows.add(assetRow);
        }
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            RowMapper<?> mapper = invocation.getArgument(1);
            try {
                if (sql.contains("COUNT(gj.id)")) {
                    return List.of(mapper.mapRow(chapterRow, 1));
                }
                if (sql.contains("JOIN audio_asset")) {
                    List<Object> mapped = new ArrayList<>();
                    for (int index = 0; index < assetRows.size(); index++) {
                        mapped.add(mapper.mapRow(assetRows.get(index), index + 1));
                    }
                    return mapped;
                }
                return List.of(mapper.mapRow(jobRow, 1));
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
