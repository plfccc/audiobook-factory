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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobClaimRepositorySqlTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    TransactionStatus transactionStatus;

    private JobClaimRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        repository = new JobClaimRepository(jdbcTemplate, transactionManager);
    }

    @Test
    void claimBindsEveryPlaceholderAndKeepsLeaseLock() {
        repository.claimNext("worker-1", Instant.parse("2026-09-05T00:00:00Z"), "scope-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), arguments.capture());

        long placeholderCount = sql.getValue().chars().filter(character -> character == '?').count();
        assertThat(placeholderCount).isEqualTo(arguments.getValue().length);
        assertThat(sql.getValue())
                .contains("WITH candidate AS")
                .doesNotContain("WITH expired AS")
                .contains("FOR UPDATE OF gj SKIP LOCKED")
                .contains("interval '5 minutes'")
                .contains("gj.scope_id IS NOT DISTINCT FROM b.active_scope_id");
    }

    @Test
    void explicitScopeIsBoundAndUsedByTheClaimStatement() {
        repository.claimNext("worker-1", Instant.parse("2026-09-05T00:00:00Z"), "scope-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), arguments.capture());

        assertThat(sql.getValue()).contains("scope_id");
        assertThat(List.of(arguments.getValue())).contains("scope-a");
    }

    @Test
    void claimRequiresTheJobScopeToMatchTheBookActiveScope() {
        repository.claimNext("worker-1", Instant.parse("2026-09-05T00:00:00Z"), "scope-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertThat(sql.getValue()).contains("gj.scope_id IS NOT DISTINCT FROM b.active_scope_id");
    }
}
