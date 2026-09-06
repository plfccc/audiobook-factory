package com.audiobookfactory.control.job;

import com.audiobookfactory.control.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JobServiceScopeTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    TransactionStatus transactionStatus;

    private JobService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        service = new JobService(
                jdbcTemplate,
                transactionManager,
                new ObjectMapper(),
                Path.of("target", "job-service-scope-test"));
    }

    @Test
    void firstScopeLessGenerationCreatesOneCoherentActiveScope() throws Exception {
        stubBookAndJobs(null);

        JobService.JobBatch batch = service.createGeneration(
                1, Map.of("chapterStart", 1, "chapterEnd", 1));

        assertThat(batch.scopeId()).startsWith("scope-");
        assertThat(updateArgumentsContaining("UPDATE generation_job")[2]).isEqualTo(batch.scopeId());
        assertThat(updateArgumentsContaining("UPDATE book SET status")[0]).isEqualTo(batch.scopeId());
    }

    @Test
    void scopeLessRebuildReusesTheBookActiveScopeForEveryJob() throws Exception {
        stubBookAndJobs("scope-legacy");

        JobService.JobBatch batch = service.createGeneration(
                1, Map.of("chapterStart", 1, "chapterEnd", 1));

        assertThat(batch.scopeId()).isEqualTo("scope-legacy");
        assertThat(updateArgumentsContaining("UPDATE generation_job")[2]).isEqualTo("scope-legacy");
        assertThat(updateArgumentsContaining("UPDATE book SET status")[0]).isEqualTo("scope-legacy");
    }

    private void stubBookAndJobs(String activeScopeId) throws Exception {
        ResultSet bookRow = org.mockito.Mockito.mock(ResultSet.class);
        when(bookRow.getLong("id")).thenReturn(1L);
        when(bookRow.getString("status")).thenReturn("RUNNING");
        when(bookRow.getString("active_scope_id")).thenReturn(activeScopeId);

        ResultSet chapterRow = org.mockito.Mockito.mock(ResultSet.class);
        when(chapterRow.getLong("id")).thenReturn(10L);
        when(chapterRow.getInt("chapter_number")).thenReturn(1);

        ResultSet jobRow = org.mockito.Mockito.mock(ResultSet.class);
        when(jobRow.getLong("id")).thenReturn(20L);

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = sql.contains("FROM book WHERE") ? bookRow
                    : sql.contains("FROM chapter c") ? chapterRow : jobRow;
            return List.of(mapper.mapRow(row, 1));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private Object[] updateArgumentsContaining(String sqlFragment) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), arguments.capture());
        for (int index = 0; index < sql.getAllValues().size(); index++) {
            if (sql.getAllValues().get(index).contains(sqlFragment)) {
                return arguments.getAllValues().get(index);
            }
        }
        throw new AssertionError("No update matched: " + sqlFragment);
    }
}
