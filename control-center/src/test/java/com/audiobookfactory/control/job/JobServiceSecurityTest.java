package com.audiobookfactory.control.job;

import com.audiobookfactory.control.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceSecurityTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    TransactionStatus transactionStatus;

    private JobService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        service = new JobService(
                jdbcTemplate,
                transactionManager,
                new ObjectMapper(),
                Path.of("target", "job-service-security-test"));
    }

    @Test
    void pausingBookReturnsActiveLeasesToWaitingAndClearsTheirOwnership() throws Exception {
        stubRows(new JobData(99, "LEASED", "worker-1", futureLease(), 7, 1, 3, 4,
                null, null, null, "RUNNING"));

        service.pauseBook(3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("UPDATE generation_job")
                .contains("THEN 'WAITING'")
                .contains("lease_owner = NULL")
                .contains("lease_expires_at = NULL"));
    }

    @Test
    void heartbeatRejectsAValidLeaseWhenItsParentBookIsPaused() throws Exception {
        stubRows(new JobData(1, "LEASED", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "PAUSED"));

        assertThatThrownBy(() -> service.heartbeat(1, "worker-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEASE_LOST"));
        verify(jdbcTemplate, never()).update(contains("SET status = CASE"), any(Object[].class));
    }

    @Test
    void resultRejectsTheLeaseWhenItsParentBookIsNoLongerRunning() throws Exception {
        byte[] audio = "wav".getBytes(StandardCharsets.UTF_8);
        stubRows(new JobData(1, "LEASED", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "PAUSED"));

        assertThatThrownBy(() -> service.recordResult(1, "worker-1",
                        new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                        metadata(audio, "durationSeconds", "1.25")))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEASE_LOST"));
    }

    @Test
    void failureRejectsTheLeaseWhenItsParentBookIsNoLongerRunning() throws Exception {
        stubRows(new JobData(1, "LEASED", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "STOPPED"));

        assertThatThrownBy(() -> service.recordFailure(1, "worker-1", "PERMANENT_FAILED", "audio invalid"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEASE_LOST"));
    }

    @Test
    void resultIdempotencyCannotBypassTheOriginalWorkerLease() throws Exception {
        byte[] audio = "wav".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(audio);
        stubRows(new JobData(1, "SUCCESS", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, sha256 + ":3", "RUNNING"));

        assertThatThrownBy(() -> service.recordResult(1, "worker-2",
                        new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                        "{\"sha256\":\"" + sha256 + "\",\"sizeBytes\":3,"
                                + "\"idempotencyKey\":\"" + sha256 + ":3\"}"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEASE_LOST"));
    }

    @Test
    void failureIdempotencyCannotReturnSuccessWithoutAValidatedLease() throws Exception {
        stubRows(new JobData(1, "FAILED", null, null, 2, 1, 3, 4,
                "PERMANENT_FAILED", "audio invalid", null, "RUNNING"));

        assertThatThrownBy(() -> service.recordFailure(1, "worker-2", "PERMANENT_FAILED", "audio invalid"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEASE_LOST"));
    }

    @Test
    void resultRejectsASha256ThatIsNotExactly64HexCharacters() {
        byte[] audio = "wav".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.recordResult(1, "worker-1",
                        new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                        "{\"sha256\":\"" + "a".repeat(63) + "\"}"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_RESULT"));
    }

    @Test
    void resultStoresWorkerDurationSecondsAsCanonicalMillisecondsAfterHashValidation() throws Exception {
        byte[] audio = "wav".getBytes(StandardCharsets.UTF_8);
        stubRows(new JobData(1, "LEASED", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "RUNNING"));

        service.recordResult(1, "worker-1",
                new MockMultipartFile("audio", "result.wav", "audio/wav", audio),
                metadata(audio, "durationSeconds", "1.25"));

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO audio_asset"), values.capture());
        assertThat(values.getValue()[3]).isEqualTo(1250L);
        assertThat(values.getValue()[7]).isEqualTo(sha256(audio));
    }

    @Test
    void controlPlaneFailureIsWaitingInsteadOfFailed() throws Exception {
        stubRows(new JobData(1, "GENERATING", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "RUNNING"));

        service.recordFailure(1, "worker-1", "AUTH_REQUIRED", "sign in required");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), values.capture());
        int generationUpdate = -1;
        for (int index = 0; index < sql.getAllValues().size(); index++) {
            if (sql.getAllValues().get(index).contains("UPDATE generation_job")) {
                generationUpdate = index;
                break;
            }
        }
        assertThat(generationUpdate).isGreaterThanOrEqualTo(0);
        assertThat(values.getAllValues().get(generationUpdate)[0]).isEqualTo("WAITING");
    }

    @Test
    void failureRejectsCodesOutsideTheBoundedControlPlaneVocabulary() {
        assertThatThrownBy(() -> service.recordFailure(1, "worker-1", "SECRET_CODE", "audio invalid"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_FAILURE"));
    }

    @Test
    void failureStoresOnlyAGenericSummaryWhenMessageContainsSensitiveMaterial() throws Exception {
        stubRows(new JobData(1, "GENERATING", "worker-1", futureLease(), 2, 1, 3, 4,
                null, null, null, "RUNNING"));
        String unsafe = "token=worker-secret prompt=private prompt\n"
                + "Traceback (most recent call last): java.lang.IllegalStateException";

        service.recordFailure(1, "worker-1", "PERMANENT_FAILED", unsafe);

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE generation_job"), values.capture());
        String persisted = (String) values.getValue()[4];
        assertThat(persisted).isEqualTo("Worker reported a failure");
        assertThat(persisted).doesNotContain("worker-secret", "private prompt", "Traceback");
        assertThat(persisted.length()).isLessThanOrEqualTo(240);
    }

    private void stubRows(JobData data) throws java.sql.SQLException {
        ResultSet jobRow = org.mockito.Mockito.mock(ResultSet.class);
        lenient().when(jobRow.getLong(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "id" -> data.id();
            case "chapter_id" -> data.chapterId();
            case "book_id" -> data.bookId();
            case "book_version_id" -> data.bookVersionId();
            default -> 0L;
        });
        lenient().when(jobRow.getInt(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "chapter_number" -> data.chapterNumber();
            case "segment_index" -> 1;
            default -> 0;
        });
        lenient().when(jobRow.getString(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "status" -> data.status();
            case "lease_owner" -> data.leaseOwner();
            case "error_code" -> data.errorCode();
            case "error_message" -> data.errorMessage();
            case "result_idempotency_key" -> data.resultIdempotencyKey();
            case "book_status" -> data.bookStatus();
            default -> null;
        });
        lenient().when(jobRow.getTimestamp(anyString())).thenAnswer(invocation ->
                "lease_expires_at".equals(invocation.getArgument(0, String.class))
                        && data.leaseExpiresAt() != null
                        ? Timestamp.from(data.leaseExpiresAt()) : null);

        ResultSet bookRow = org.mockito.Mockito.mock(ResultSet.class);
        lenient().when(bookRow.getLong("id")).thenReturn(data.bookId());
        lenient().when(bookRow.getString("status")).thenReturn(data.bookStatus());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = sql.contains("FROM generation_job") ? jobRow : bookRow;
            try {
                return List.of(mapper.mapRow(row, 1));
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private Instant futureLease() {
        return Instant.now().plusSeconds(60);
    }

    private String metadata(byte[] audio, String durationName, String durationValue) {
        return "{\"sha256\":\"" + sha256(audio) + "\",\"sizeBytes\":" + audio.length
                + ",\"" + durationName + "\":" + durationValue + "}";
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record JobData(long id, String status, String leaseOwner, Instant leaseExpiresAt,
                           long chapterId, int chapterNumber, long bookId, long bookVersionId,
                           String errorCode, String errorMessage, String resultIdempotencyKey,
                           String bookStatus) {
    }
}
