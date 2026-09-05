package com.audiobookfactory.control.book;

import com.audiobookfactory.control.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class EpubImportService {

    private static final String PARSER_VERSION = "v1";

    private final BookRepository repository;
    private final BookStorage storage;
    private final EpubChapterExtractor chapterExtractor;
    private final TextSegmenter segmenter;
    private final SegmentationPolicy segmentationPolicy;

    @Autowired
    public EpubImportService(JdbcTemplate jdbcTemplate, AppProperties appProperties) {
        this(
                new JdbcBookRepository(jdbcTemplate),
                new FileBookStorage(appProperties.storageRoot()),
                new EpubChapterExtractor(),
                new TextSegmenter(),
                SegmentationPolicy.notebookDefaults());
    }

    public EpubImportService(BookRepository repository, Path storageRoot) {
        this(repository, storageRoot, new EpubChapterExtractor(), new TextSegmenter(),
                SegmentationPolicy.notebookDefaults());
    }

    public EpubImportService(BookRepository repository, BookStorage storage) {
        this(repository, storage, new EpubChapterExtractor(), new TextSegmenter(),
                SegmentationPolicy.notebookDefaults());
    }

    public EpubImportService(BookRepository repository, Path storageRoot,
                             EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                             SegmentationPolicy segmentationPolicy) {
        this(repository, new FileBookStorage(storageRoot), chapterExtractor, segmenter, segmentationPolicy);
    }

    public EpubImportService(BookRepository repository, BookStorage storage,
                             EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                             SegmentationPolicy segmentationPolicy) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.chapterExtractor = Objects.requireNonNull(chapterExtractor, "chapterExtractor must not be null");
        this.segmenter = Objects.requireNonNull(segmenter, "segmenter must not be null");
        this.segmentationPolicy = Objects.requireNonNull(segmentationPolicy, "segmentationPolicy must not be null");
    }

    @Transactional
    public synchronized BookImportResult importBook(InputStream source, String originalFilename) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename must not be blank");
        }

        StagedSource staged = null;
        List<String> storedFiles = new ArrayList<>();
        String sourceSha256 = null;
        try {
            staged = storage.stage(source);
            sourceSha256 = staged.sha256();
            Optional<StoredBookVersion> existing = repository.findBySourceSha256(sourceSha256);
            if (existing.isPresent()) {
                StoredBookVersion version = existing.get();
                return new BookImportResult(
                        version.bookId(), version.bookVersionId(), version.chapterCount(), sourceSha256);
            }

            List<ChapterDraft> chapters = chapterExtractor.extract(staged.path());
            if (chapters.isEmpty()) {
                throw new IllegalArgumentException("EPUB does not contain readable spine chapters");
            }

            String sourcePath = storage.storeSource(staged.path(), originalFilename, sourceSha256);
            storedFiles.add(sourcePath);
            staged = null;

            long bookId = repository.createBook(bookTitle(originalFilename));
            long bookVersionId = repository.createBookVersion(
                    bookId,
                    sourcePath,
                    sourceSha256,
                    PARSER_VERSION,
                    segmentationPolicy.rulesVersion(),
                    chapters.size());
            for (int chapterOffset = 0; chapterOffset < chapters.size(); chapterOffset++) {
                ChapterDraft chapter = chapters.get(chapterOffset);
                int chapterNumber = chapterOffset + 1;
                String textPath = storage.storeChapterText(bookId, bookVersionId, chapterNumber, chapter.text());
                storedFiles.add(textPath);
                long chapterId = repository.createChapter(
                        bookVersionId,
                        chapterNumber,
                        chapter.title(),
                        textPath,
                        chapter.textSha256());
                for (SegmentDraft segment : segmenter.segment(chapter.text(), segmentationPolicy)) {
                    repository.createGenerationJob(
                            chapterId,
                            segment.index(),
                            segment.text(),
                            segment.textSha256());
                }
            }
            return new BookImportResult(bookId, bookVersionId, chapters.size(), sourceSha256);
        } catch (IOException exception) {
            deleteStoredFiles(storedFiles);
            throw new IllegalStateException("Unable to import EPUB", exception);
        } catch (RuntimeException exception) {
            deleteStoredFiles(storedFiles);
            throw exception;
        } finally {
            if (staged != null) {
                try {
                    storage.delete(staged.path().toString());
                } catch (IOException ignored) {
                    // 临时文件清理失败不应覆盖原始导入错误。
                }
            }
        }
    }

    private void deleteStoredFiles(List<String> storedFiles) {
        for (int index = storedFiles.size() - 1; index >= 0; index--) {
            try {
                storage.delete(storedFiles.get(index));
            } catch (IOException ignored) {
                // 数据库事务会回滚；残留文件不应覆盖导致回滚的异常。
            }
        }
    }

    private String bookTitle(String originalFilename) {
        String leaf = originalFilename.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) {
            leaf = leaf.substring(slash + 1);
        }
        leaf = leaf.replaceAll("[\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        while (leaf.contains("..")) {
            leaf = leaf.replace("..", "_");
        }
        int extension = leaf.toLowerCase(Locale.ROOT).endsWith(".epub") ? leaf.length() - 5 : -1;
        if (extension > 0) {
            leaf = leaf.substring(0, extension);
        }
        return leaf.isBlank() ? "Untitled" : leaf;
    }

    public interface BookRepository {

        Optional<StoredBookVersion> findBySourceSha256(String sourceSha256);

        long createBook(String title);

        long createBookVersion(long bookId, String sourceFilePath, String sourceSha256,
                               String parserVersion, String segmentationRuleVersion, int chapterCount);

        long createChapter(long bookVersionId, int chapterNumber, String title,
                           String textPath, String textSha256);

        void createGenerationJob(long chapterId, int segmentIndex, String segmentText, String textSha256);
    }

    public interface BookStorage {

        StagedSource stage(InputStream source) throws IOException;

        String storeSource(Path stagedSource, String originalFilename, String sourceSha256) throws IOException;

        String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text) throws IOException;

        void delete(String storedPath) throws IOException;
    }

    public record StoredBookVersion(
            long bookId,
            long bookVersionId,
            int chapterCount,
            String sourceFilePath,
            String sourceSha256) {
    }

    public record StagedSource(Path path, String sha256) {
    }

    public static final class FileBookStorage implements BookStorage {

        private final Path storageRoot;

        public FileBookStorage(Path storageRoot) {
            this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot must not be null")
                    .toAbsolutePath().normalize();
        }

        @Override
        public StagedSource stage(InputStream source) throws IOException {
            Files.createDirectories(storageRoot);
            Path staged = Files.createTempFile(storageRoot, "upload-", ".part");
            try {
                MessageDigest digest = sha256Digest();
                try (OutputStream output = Files.newOutputStream(staged, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = source.read(buffer)) != -1) {
                        if (read == 0) {
                            continue;
                        }
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                return new StagedSource(staged, java.util.HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(staged);
                throw exception;
            }
        }

        @Override
        public String storeSource(Path stagedSource, String originalFilename, String sourceSha256) throws IOException {
            Path targetDirectory = storageRoot.resolve("sources").normalize();
            Path target = targetDirectory.resolve(sourceSha256 + "-" + sanitizeFilename(originalFilename)).normalize();
            ensureWithinRoot(target);
            Files.createDirectories(targetDirectory);
            move(stagedSource, target);
            return target.toString();
        }

        @Override
        public String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text)
                throws IOException {
            Path directory = storageRoot.resolve("books").resolve(Long.toString(bookId))
                    .resolve("versions").resolve(Long.toString(bookVersionId)).resolve("chapters").normalize();
            Path target = directory.resolve(chapterNumber + ".txt").normalize();
            ensureWithinRoot(target);
            Files.createDirectories(directory);
            Files.writeString(target, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target.toString();
        }

        @Override
        public void delete(String storedPath) throws IOException {
            Path path = Path.of(storedPath).toAbsolutePath().normalize();
            ensureWithinRoot(path);
            Files.deleteIfExists(path);
        }

        private void ensureWithinRoot(Path path) {
            if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
                throw new IllegalArgumentException("Storage path escapes storage root: " + path);
            }
        }

        private void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target);
            }
        }

        private String sanitizeFilename(String originalFilename) {
            String leaf = originalFilename.replace('\\', '/');
            int slash = leaf.lastIndexOf('/');
            if (slash >= 0) {
                leaf = leaf.substring(slash + 1);
            }
            leaf = leaf.replaceAll("[^A-Za-z0-9._-]", "_");
            while (leaf.contains("..")) {
                leaf = leaf.replace("..", "_");
            }
            if (leaf.isBlank() || ".".equals(leaf) || "_".equals(leaf)) {
                leaf = "book.epub";
            }
            return leaf.length() > 120 ? leaf.substring(0, 120) : leaf;
        }

        private MessageDigest sha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is not available", exception);
            }
        }
    }

    static final class JdbcBookRepository implements BookRepository {

        private final JdbcTemplate jdbcTemplate;

        JdbcBookRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        }

        @Override
        public Optional<StoredBookVersion> findBySourceSha256(String sourceSha256) {
            try {
                StoredBookVersion version = jdbcTemplate.queryForObject(
                        "SELECT bv.book_id, bv.id, bv.source_file_path, bv.source_file_sha256, "
                                + "COUNT(c.id) AS chapter_count "
                                + "FROM book_version bv LEFT JOIN chapter c ON c.book_version_id = bv.id "
                                + "WHERE bv.source_file_sha256 = ? "
                                + "GROUP BY bv.book_id, bv.id, bv.source_file_path, bv.source_file_sha256 "
                                + "ORDER BY bv.id LIMIT 1",
                        (resultSet, rowNum) -> new StoredBookVersion(
                                resultSet.getLong("book_id"),
                                resultSet.getLong("id"),
                                resultSet.getInt("chapter_count"),
                                resultSet.getString("source_file_path"),
                                resultSet.getString("source_file_sha256").trim()),
                        sourceSha256);
                return Optional.of(version);
            } catch (EmptyResultDataAccessException exception) {
                return Optional.empty();
            }
        }

        @Override
        public long createBook(String title) {
            return insert("INSERT INTO book (title) VALUES (?)", title);
        }

        @Override
        public long createBookVersion(long bookId, String sourceFilePath, String sourceSha256,
                                      String parserVersion, String segmentationRuleVersion, int chapterCount) {
            return insert(
                    "INSERT INTO book_version (book_id, source_file_path, source_file_sha256, "
                            + "parser_version, segmentation_rule_version) VALUES (?, ?, ?, ?, ?)",
                    bookId, sourceFilePath, sourceSha256, parserVersion, segmentationRuleVersion);
        }

        @Override
        public long createChapter(long bookVersionId, int chapterNumber, String title,
                                  String textPath, String textSha256) {
            return insert(
                    "INSERT INTO chapter (book_version_id, chapter_number, title, text_path, text_sha256) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    bookVersionId, chapterNumber, title, textPath, textSha256);
        }

        @Override
        public void createGenerationJob(long chapterId, int segmentIndex, String segmentText, String textSha256) {
            jdbcTemplate.update(
                    "INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, preset_snapshot) "
                            + "VALUES (?, ?, ?, ?, '{}'::jsonb)",
                    chapterId, segmentIndex, segmentText, textSha256);
        }

        private long insert(String sql, Object... arguments) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int index = 0; index < arguments.length; index++) {
                    statement.setObject(index + 1, arguments[index]);
                }
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("Database insert did not return an id");
            }
            return key.longValue();
        }
    }
}
