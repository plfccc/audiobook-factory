package com.audiobookfactory.control.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
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
        assertThat(sql.getValue()).contains("FOR UPDATE OF gj SKIP LOCKED")
                .contains("interval '5 minutes'")
                .contains("status = 'WAITING'")
                .contains("lease_owner = NULL")
                .contains("lease_expires_at = NULL")
                .contains("heartbeat_at = NULL")
                .contains("error_code = NULL")
                .contains("error_message = NULL")
                .contains("next_retry_at = NULL");
        assertThat(boundArguments).hasSize((int) sql.getValue().chars()
                .filter(character -> character == '?')
                .count());
        assertThat(boundArguments).containsExactly(
                timestamp,
                timestamp,
                timestamp,
                null,
                "worker-1",
                timestamp,
                timestamp,
                timestamp,
                timestamp);
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
                        timestamp,
                        timestamp,
                        timestamp,
                        "scope-a",
                        "worker-1",
                        timestamp,
                        timestamp,
                        timestamp,
                        timestamp);
    }
}
