package com.audiobookfactory.control.progress;

import com.audiobookfactory.control.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class ProgressQueryService {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ProgressQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public ChapterProgress get(long bookId) {
        if (bookId <= 0) {
            throw new ApiException("INVALID_ID", 400, "bookId must be positive");
        }
        Boolean bookExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM book WHERE id = ?)", Boolean.class, bookId);
        if (!Boolean.TRUE.equals(bookExists)) {
            throw new ApiException("BOOK_NOT_FOUND", 404, "Book was not found");
        }

        List<ChapterProgress> progress = jdbcTemplate.query("""
                SELECT COUNT(*) FILTER (WHERE c.status = 'SUCCESS') AS completed_chapters,
                       COUNT(*) AS total_chapters,
                       MIN(c.chapter_number) FILTER (WHERE c.status <> 'SUCCESS') AS current_chapter
                FROM chapter c
                JOIN book_version bv ON bv.id = c.book_version_id
                WHERE bv.book_id = ?
                  AND bv.id = (
                      SELECT latest.id FROM book_version latest
                      WHERE latest.book_id = bv.book_id
                      ORDER BY latest.id DESC LIMIT 1
                  )
                """, (resultSet, rowNum) -> new ChapterProgress(
                resultSet.getInt("completed_chapters"),
                resultSet.getInt("total_chapters"),
                resultSet.getObject("current_chapter", Integer.class)), bookId);
        if (progress.isEmpty()) {
            return new ChapterProgress(0, 0, null);
        }
        return progress.get(0);
    }

    public ChapterProgress get(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new ApiException("INVALID_ID", 400, "bookId must be positive");
        }
        try {
            return get(Long.parseLong(bookId));
        } catch (NumberFormatException exception) {
            throw new ApiException("INVALID_ID", 400, "bookId must be positive");
        }
    }
}
