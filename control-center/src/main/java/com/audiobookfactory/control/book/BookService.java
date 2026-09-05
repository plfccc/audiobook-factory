package com.audiobookfactory.control.book;

import com.audiobookfactory.control.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class BookService {

    private static final String BOOK_AGGREGATE_SQL = """
            SELECT b.id, b.title, b.author, b.status, b.created_at, b.updated_at,
                   bv.id AS book_version_id, bv.source_file_sha256,
                   COUNT(c.id) AS chapter_count,
                   COUNT(c.id) FILTER (WHERE c.status = 'SUCCESS') AS completed_chapters
            FROM book b
            LEFT JOIN book_version bv ON bv.id = (
                SELECT latest.id FROM book_version latest
                WHERE latest.book_id = b.id
                ORDER BY latest.id DESC LIMIT 1
            )
            LEFT JOIN chapter c ON c.book_version_id = bv.id
            %s
            GROUP BY b.id, b.title, b.author, b.status, b.created_at, b.updated_at,
                     bv.id, bv.source_file_sha256
            %s
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EpubImportService importService;

    @Autowired
    public BookService(JdbcTemplate jdbcTemplate, EpubImportService importService) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.importService = Objects.requireNonNull(importService, "importService must not be null");
    }

    public EpubImportService.ImportOutcome importBook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("INVALID_EPUB_INPUT", 400, "EPUB file must not be empty");
        }
        String filename = file.getOriginalFilename();
        try (InputStream input = file.getInputStream()) {
            return importService.importBookDetailed(input, filename);
        } catch (IOException exception) {
            throw new ApiException("INVALID_EPUB_INPUT", 400, "Unable to read EPUB upload");
        }
    }

    public List<BookSummary> listBooks() {
        String sql = BOOK_AGGREGATE_SQL.formatted("", "ORDER BY b.created_at DESC, b.id DESC");
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> new BookSummary(
                resultSet.getLong("id"),
                resultSet.getString("title"),
                resultSet.getString("author"),
                resultSet.getString("status"),
                toInt(resultSet.getLong("chapter_count")),
                toInt(resultSet.getLong("completed_chapters")),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")));
    }

    public BookDetail getBook(long bookId) {
        requirePositiveId(bookId, "bookId");
        String sql = BOOK_AGGREGATE_SQL.formatted("WHERE b.id = ?", "");
        List<BookDetail> books = jdbcTemplate.query(sql, (resultSet, rowNum) -> new BookDetail(
                resultSet.getLong("id"),
                resultSet.getString("title"),
                resultSet.getString("author"),
                resultSet.getString("status"),
                resultSet.getObject("book_version_id", Long.class),
                trim(resultSet.getString("source_file_sha256")),
                toInt(resultSet.getLong("chapter_count")),
                toInt(resultSet.getLong("completed_chapters")),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")), bookId);
        if (books.isEmpty()) {
            throw bookNotFound(bookId);
        }
        return books.get(0);
    }

    public List<ChapterView> listChapters(long bookId) {
        requirePositiveId(bookId, "bookId");
        ensureBookExists(bookId);
        String sql = """
                SELECT c.id, c.chapter_number, c.title, c.status, c.final_audio_path,
                       COUNT(gj.id) AS segment_count,
                       COUNT(gj.id) FILTER (WHERE gj.status = 'SUCCESS') AS completed_segments
                FROM chapter c
                JOIN book_version bv ON bv.id = c.book_version_id
                LEFT JOIN generation_job gj ON gj.chapter_id = c.id
                WHERE bv.book_id = ?
                  AND bv.id = (
                      SELECT latest.id FROM book_version latest
                      WHERE latest.book_id = bv.book_id
                      ORDER BY latest.id DESC LIMIT 1
                  )
                GROUP BY c.id, c.chapter_number, c.title, c.status, c.final_audio_path
                ORDER BY c.chapter_number
                """;
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> new ChapterView(
                resultSet.getLong("id"),
                resultSet.getInt("chapter_number"),
                resultSet.getString("title"),
                resultSet.getString("status"),
                toInt(resultSet.getLong("segment_count")),
                toInt(resultSet.getLong("completed_segments")),
                resultSet.getString("final_audio_path") != null
                        ? "/api/v1/chapters/" + resultSet.getLong("id") + "/audio"
                        : null), bookId);
    }

    public List<SegmentView> listSegments(long bookId, String chapterIdOrNumber) {
        requirePositiveId(bookId, "bookId");
        long chapterReference = parsePositiveId(chapterIdOrNumber, "chapterId");
        String sql = """
                SELECT gj.id, c.chapter_number, gj.segment_index, gj.segment_text,
                       gj.text_sha256, gj.status, gj.attempts, gj.error_code, gj.error_message
                FROM generation_job gj
                JOIN chapter c ON c.id = gj.chapter_id
                JOIN book_version bv ON bv.id = c.book_version_id
                WHERE bv.book_id = ?
                  AND bv.id = (
                      SELECT latest.id FROM book_version latest
                      WHERE latest.book_id = bv.book_id
                      ORDER BY latest.id DESC LIMIT 1
                  )
                  AND (c.id = ? OR c.chapter_number = ?)
                ORDER BY gj.segment_index, gj.id
                """;
        List<SegmentView> segments = jdbcTemplate.query(sql, (resultSet, rowNum) -> new SegmentView(
                Long.toString(resultSet.getLong("id")),
                resultSet.getInt("chapter_number"),
                resultSet.getInt("segment_index"),
                resultSet.getString("segment_text"),
                trim(resultSet.getString("text_sha256")),
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message")),
                bookId, chapterReference, chapterReference);
        if (!segments.isEmpty()) {
            return segments;
        }
        if (!chapterExists(bookId, chapterReference)) {
            throw new ApiException("CHAPTER_NOT_FOUND", 404, "Chapter not found");
        }
        return List.of();
    }

    public void ensureBookExists(long bookId) {
        requirePositiveId(bookId, "bookId");
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM book WHERE id = ?)", Boolean.class, bookId);
        if (!Boolean.TRUE.equals(exists)) {
            throw bookNotFound(bookId);
        }
    }

    private boolean chapterExists(long bookId, long chapterReference) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM chapter c
                    JOIN book_version bv ON bv.id = c.book_version_id
                    WHERE bv.book_id = ?
                      AND bv.id = (
                          SELECT latest.id FROM book_version latest
                          WHERE latest.book_id = bv.book_id
                          ORDER BY latest.id DESC LIMIT 1
                      )
                      AND (c.id = ? OR c.chapter_number = ?)
                )
                """, Boolean.class, bookId, chapterReference, chapterReference);
        return Boolean.TRUE.equals(exists);
    }

    private void requirePositiveId(long id, String name) {
        if (id <= 0) {
            throw new ApiException("INVALID_ID", 400, name + " must be positive");
        }
    }

    private long parsePositiveId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ApiException("INVALID_ID", 400, name + " must be positive");
        }
        try {
            long parsed = Long.parseLong(value);
            requirePositiveId(parsed, name);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ApiException("INVALID_ID", 400, name + " must be positive");
        }
    }

    private ApiException bookNotFound(long bookId) {
        return new ApiException("BOOK_NOT_FOUND", 404, "Book " + bookId + " was not found");
    }

    private Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    public record BookSummary(
            long id,
            String title,
            String author,
            String status,
            int chapterCount,
            int completedChapters,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record BookDetail(
            long id,
            String title,
            String author,
            String status,
            Long bookVersionId,
            String sourceSha256,
            int chapterCount,
            int completedChapters,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ChapterView(
            long id,
            int chapterNumber,
            String title,
            String status,
            int segmentCount,
            int completedSegments,
            String audioUrl) {
    }

    public record SegmentView(
            String jobId,
            int chapterNumber,
            int segmentIndex,
            String text,
            String textSha256,
            String status,
            int attempts,
            String errorCode,
            String errorMessage) {
    }
}
