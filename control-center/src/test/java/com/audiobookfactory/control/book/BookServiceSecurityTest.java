package com.audiobookfactory.control.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceSecurityTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    EpubImportService importService;

    @Test
    void listSegmentsSanitizesLegacyFailureDetailsBeforeReturningBookApiData() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getInt("chapter_number")).thenReturn(1);
        when(row.getInt("segment_index")).thenReturn(1);
        when(row.getInt("attempts")).thenReturn(2);
        when(row.getString("segment_text")).thenReturn("Hello");
        when(row.getString("text_sha256")).thenReturn("a".repeat(64));
        when(row.getString("status")).thenReturn("FAILED");
        when(row.getString("error_code")).thenReturn("SECRET_CODE");
        when(row.getString("error_message")).thenReturn(
                "You are a helpful assistant. Read this sentence aloud.\n"
                        + "at com.example.worker.Worker.run(Worker.java:42)\n"
                        + "C:\\service\\worker\\logs\\worker.log");
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 1));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        List<BookService.SegmentView> segments = new BookService(jdbcTemplate, importService)
                .listSegments(7, "2");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).errorCode()).isEqualTo("UNKNOWN_FAILURE");
        assertThat(segments.get(0).errorMessage()).isEqualTo("Worker reported a failure");
        assertThat(segments.get(0).errorMessage())
                .doesNotContain("helpful assistant", "Worker.java", "C:\\service");
    }
}
