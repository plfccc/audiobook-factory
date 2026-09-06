package com.audiobookfactory.control.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobClaimRepositoryBindingTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    TransactionStatus transactionStatus;

    private JobClaimRepository repository;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        repository = new JobClaimRepository(jdbcTemplate, transactionManager);
    }

    @Test
    void claimBindsEveryPlaceholderInTheClaimSqlInOrder() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        repository.claimNext("worker-1", now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), arguments.capture());

        Timestamp timestamp = Timestamp.from(now);
        Object[] boundArguments = arguments.getValue();
        assertThat(sql.getValue()).contains("WITH candidate AS")
                .doesNotContain("WITH expired AS")
                .contains("FOR UPDATE OF gj SKIP LOCKED")
                .contains("interval '5 minutes'")
                .contains("gj.scope_id IS NOT DISTINCT FROM b.active_scope_id");
        assertThat(boundArguments).hasSize((int) sql.getValue().chars()
                .filter(character -> character == '?')
                .count());
        assertThat(boundArguments).containsExactly(
                null,
                timestamp,
                "worker-1",
                timestamp,
                timestamp,
                timestamp,
                timestamp);
    }

    @Test
    void expiredLeaseCleanupRunsBeforeCandidateSelectionInTheSameTransaction() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        repository.claimNext("worker-1", now);

        ArgumentCaptor<String> cleanupSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> cleanupArguments = ArgumentCaptor.forClass(Object[].class);
        InOrder calls = inOrder(jdbcTemplate);
        calls.verify(jdbcTemplate).update(cleanupSql.capture(), cleanupArguments.capture());
        calls.verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        Timestamp timestamp = Timestamp.from(now);
        assertThat(cleanupSql.getValue())
                .contains("UPDATE generation_job")
                .contains("status = 'WAITING'")
                .contains("lease_owner = NULL")
                .contains("lease_expires_at = NULL")
                .contains("heartbeat_at = NULL")
                .contains("error_code = NULL")
                .contains("error_message = NULL")
                .contains("next_retry_at = NULL")
                .doesNotContain("WITH expired AS");
        assertThat(cleanupArguments.getValue()).containsExactly(timestamp, timestamp);
    }

    @Test
    void sameClaimCallCanReturnAJobOnlyAfterExpiredLeaseCleanup() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        JobClaim reclaimed = new JobClaim(1, 1, 1, 1, 1, 1, "segment", "{}",
                "worker-1", now.plusSeconds(JobClaimRepository.LEASE_SECONDS), 1);
        AtomicBoolean cleanupRan = new AtomicBoolean();
        doAnswer(invocation -> {
            cleanupRan.set(true);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object[].class));
        doAnswer(invocation -> cleanupRan.get() ? List.of(reclaimed) : List.of())
                .when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(repository.claimNext("worker-1", now)).isSameAs(reclaimed);
    }

    @Test
    void scopedClaimBindsTheScopeBeforeLeaseArguments() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        repository.claimNext("worker-1", now, "scope-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), arguments.capture());

        Timestamp timestamp = Timestamp.from(now);
        assertThat(sql.getValue()).contains("scope_id");
        assertThat(arguments.getValue()).hasSize((int) sql.getValue().chars()
                .filter(character -> character == '?')
                .count())
                .containsExactly(
                        "scope-a",
                        timestamp,
                        "worker-1",
                        timestamp,
                        timestamp,
                        timestamp,
                        timestamp);
    }
}
