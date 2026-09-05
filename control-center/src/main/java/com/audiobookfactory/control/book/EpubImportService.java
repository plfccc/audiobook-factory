package com.audiobookfactory.control.book;

import com.audiobookfactory.control.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class EpubImportService {

    public static final long DEFAULT_MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    public static final String EPUB_SOURCE_TOO_LARGE = "EPUB_SOURCE_TOO_LARGE";
    public static final String EPUB_IMPORT_RECOVERY_UNAVAILABLE =
            "EPUB_IMPORT_RECOVERY_UNAVAILABLE";
    public static final String EPUB_IMPORT_RECOVERY_FAILED = "EPUB_IMPORT_RECOVERY_FAILED";

    private static final String PARSER_VERSION = "v1";
    private static final String PRESET_SNAPSHOT = "{}";

    private final BookRepository repository;
    private final BookStorage storage;
    private final EpubChapterExtractor chapterExtractor;
    private final TextSegmenter segmenter;
    private final SegmentationPolicy segmentationPolicy;
    private final TransactionTemplate transactionTemplate;
    private final ImportLimits importLimits;

    @Autowired
    public EpubImportService(JdbcTemplate jdbcTemplate, AppProperties appProperties,
                             PlatformTransactionManager transactionManager) {
        this(
                new JdbcBookRepository(jdbcTemplate),
                new FileBookStorage(appProperties.storageRoot()),
                new EpubChapterExtractor(),
                new TextSegmenter(),
                SegmentationPolicy.notebookDefaults(),
                new TransactionTemplate(transactionManager),
                ImportLimits.defaults());
    }

    public EpubImportService(JdbcTemplate jdbcTemplate, AppProperties appProperties) {
        this(
                new JdbcBookRepository(jdbcTemplate),
                new FileBookStorage(appProperties.storageRoot()),
                new EpubChapterExtractor(),
                new TextSegmenter(),
                SegmentationPolicy.notebookDefaults(),
                null,
                ImportLimits.defaults());
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
        this(repository, new FileBookStorage(storageRoot), chapterExtractor, segmenter,
                segmentationPolicy, null, ImportLimits.defaults());
    }

    public EpubImportService(BookRepository repository, Path storageRoot,
                             EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                             SegmentationPolicy segmentationPolicy, ImportLimits importLimits) {
        this(repository, new FileBookStorage(storageRoot), chapterExtractor, segmenter,
                segmentationPolicy, null, importLimits);
    }

    public EpubImportService(BookRepository repository, BookStorage storage,
                             EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                             SegmentationPolicy segmentationPolicy) {
        this(repository, storage, chapterExtractor, segmenter, segmentationPolicy,
                null, ImportLimits.defaults());
    }

    public EpubImportService(BookRepository repository, BookStorage storage,
                             EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                             SegmentationPolicy segmentationPolicy, ImportLimits importLimits) {
        this(repository, storage, chapterExtractor, segmenter, segmentationPolicy,
                null, importLimits);
    }

    private EpubImportService(BookRepository repository, BookStorage storage,
                              EpubChapterExtractor chapterExtractor, TextSegmenter segmenter,
                              SegmentationPolicy segmentationPolicy, TransactionTemplate transactionTemplate,
                              ImportLimits importLimits) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.chapterExtractor = Objects.requireNonNull(chapterExtractor, "chapterExtractor must not be null");
        this.segmenter = Objects.requireNonNull(segmenter, "segmenter must not be null");
        this.segmentationPolicy = Objects.requireNonNull(segmentationPolicy,
                "segmentationPolicy must not be null");
        this.transactionTemplate = transactionTemplate;
        this.importLimits = Objects.requireNonNull(importLimits, "importLimits must not be null");
    }

    public BookImportResult importBook(InputStream source, String originalFilename) {
        validateUploadName(source, originalFilename);
        reconcileStaging();

        ImportWorkspace workspace = null;
        boolean databaseCommitted = false;
        try {
            workspace = storage.beginImport();
            StagedSource staged = workspace.stage(
                    new LimitedInputStream(source, importLimits.maxSourceBytes()));
            Optional<StoredBookVersion> existing = repository.findBySourceSha256(staged.sha256());
            if (existing.isPresent()) {
                return toImportResult(existing.get());
            }

            ImportWorkspace activeWorkspace = workspace;
            BookImportResult result;
            try {
                result = executeInTransaction(() -> importNewBook(
                        activeWorkspace, staged, originalFilename));
            } catch (DataIntegrityViolationException exception) {
                abortWorkspace(activeWorkspace);
                Optional<StoredBookVersion> winner = repository.findBySourceSha256(staged.sha256());
                if (winner.isPresent()) {
                    return toImportResult(winner.get());
                }
                throw exception;
            } catch (RuntimeException exception) {
                abortWorkspace(activeWorkspace);
                throw exception;
            }

            databaseCommitted = true;
            try {
                activeWorkspace.databaseCommitted();
                activeWorkspace.promote();
                activeWorkspace.finish();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "EPUB database commit succeeded but file promotion is incomplete", exception);
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to stage EPUB upload", exception);
        } finally {
            if (workspace != null) {
                if (databaseCommitted) {
                    closeWorkspace(workspace);
                } else {
                    abortWorkspace(workspace);
                }
            }
        }
    }

    public void reconcileStaging() {
        try {
            storage.reconcile(repository);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to reconcile EPUB import staging", exception);
        }
    }

    private BookImportResult executeInTransaction(ImportWork work) {
        if (transactionTemplate == null) {
            return work.run();
        }
        BookImportResult result = transactionTemplate.execute(status -> work.run());
        if (result == null) {
            throw new IllegalStateException("EPUB import transaction returned no result");
        }
        return result;
    }

    private BookImportResult importNewBook(ImportWorkspace workspace, StagedSource staged,
                                           String originalFilename) {
        try {
            List<ChapterDraft> chapters = chapterExtractor.extract(staged.path());
            if (chapters.isEmpty()) {
                throw new EpubImportException(
                        "INVALID_EPUB_STRUCTURE", "EPUB does not contain readable spine chapters");
            }

            String sourcePath = workspace.storeSource(staged.path(), originalFilename, staged.sha256());

            long bookId = repository.createBook(bookTitle(originalFilename));
            long bookVersionId = repository.createBookVersion(
                    bookId,
                    sourcePath,
                    staged.sha256(),
                    PARSER_VERSION,
                    segmentationPolicy.rulesVersion(),
                    chapters.size());
            workspace.recordBookVersion(bookId, bookVersionId);
            for (int chapterOffset = 0; chapterOffset < chapters.size(); chapterOffset++) {
                ChapterDraft chapter = chapters.get(chapterOffset);
                int chapterNumber = chapterOffset + 1;
                String textPath = workspace.storeChapterText(
                        bookId, bookVersionId, chapterNumber, chapter.text());
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
                            segment.textSha256(),
                            PRESET_SNAPSHOT);
                }
            }
            return new BookImportResult(bookId, bookVersionId, chapters.size(), staged.sha256());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to import EPUB", exception);
        }
    }

    private BookImportResult toImportResult(StoredBookVersion version) {
        return new BookImportResult(
                version.bookId(), version.bookVersionId(), version.chapterCount(), version.sourceSha256());
    }

    private void abortWorkspace(ImportWorkspace workspace) {
        try {
            workspace.abort();
        } catch (IOException | RuntimeException ignored) {
            // journal 会保留到下一次 reconcile，不能覆盖原始导入错误。
        }
    }

    private void closeWorkspace(ImportWorkspace workspace) {
        try {
            workspace.close();
        } catch (IOException | RuntimeException ignored) {
            // 已提交导入保留 journal，下一次 reconcile 会继续处理。
        }
    }

    private void validateUploadName(InputStream source, String originalFilename) {
        if (source == null) {
            throw new EpubImportException("INVALID_EPUB_INPUT", "source must not be null");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new EpubImportException("INVALID_EPUB_INPUT", "originalFilename must not be blank");
        }
        String filename = filenameLeaf(originalFilename);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new EpubImportException(
                    EpubChapterExtractor.INVALID_EPUB_EXTENSION,
                    "EPUB upload must use the .epub extension");
        }
    }

    private String bookTitle(String originalFilename) {
        String leaf = filenameLeaf(originalFilename);
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

    private static String filenameLeaf(String originalFilename) {
        String leaf = originalFilename.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        return slash >= 0 ? leaf.substring(slash + 1) : leaf;
    }

    public interface BookRepository {

        Optional<StoredBookVersion> findBySourceSha256(String sourceSha256);

        long createBook(String title);

        long createBookVersion(long bookId, String sourceFilePath, String sourceSha256,
                               String parserVersion, String segmentationRuleVersion, int chapterCount);

        long createChapter(long bookVersionId, int chapterNumber, String title,
                           String textPath, String textSha256);

        default void createGenerationJob(long chapterId, int segmentIndex, String segmentText,
                                         String textSha256) {
            createGenerationJob(chapterId, segmentIndex, segmentText, textSha256, PRESET_SNAPSHOT);
        }

        default void createGenerationJob(long chapterId, int segmentIndex, String segmentText,
                                         String textSha256, String presetSnapshot) {
            createGenerationJob(chapterId, segmentIndex, segmentText, textSha256);
        }
    }

    public interface BookStorage {

        StagedSource stage(InputStream source) throws IOException;

        String storeSource(Path stagedSource, String originalFilename, String sourceSha256) throws IOException;

        String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text) throws IOException;

        void delete(String storedPath) throws IOException;

        default ImportWorkspace beginImport() throws IOException {
            return new LegacyImportWorkspace(this);
        }

        default void reconcile(BookRepository repository) throws IOException {
        }
    }

    public interface ImportWorkspace {

        StagedSource stage(InputStream source) throws IOException;

        String storeSource(Path stagedSource, String originalFilename, String sourceSha256) throws IOException;

        String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text)
                throws IOException;

        void recordBookVersion(long bookId, long bookVersionId) throws IOException;

        void databaseCommitted() throws IOException;

        void promote() throws IOException;

        void finish() throws IOException;

        void abort() throws IOException;

        void close() throws IOException;
    }

    private static final class LegacyImportWorkspace implements ImportWorkspace {

        private final BookStorage storage;
        private final List<String> storedPaths = new ArrayList<>();
        private Path stagedPath;
        private boolean aborted;

        private LegacyImportWorkspace(BookStorage storage) {
            this.storage = storage;
        }

        @Override
        public StagedSource stage(InputStream source) throws IOException {
            StagedSource staged = storage.stage(source);
            stagedPath = staged.path();
            return staged;
        }

        @Override
        public String storeSource(Path stagedSource, String originalFilename, String sourceSha256)
                throws IOException {
            String storedPath = storage.storeSource(stagedSource, originalFilename, sourceSha256);
            storedPaths.add(storedPath);
            return storedPath;
        }

        @Override
        public String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text)
                throws IOException {
            String storedPath = storage.storeChapterText(bookId, bookVersionId, chapterNumber, text);
            storedPaths.add(storedPath);
            return storedPath;
        }

        @Override
        public void recordBookVersion(long bookId, long bookVersionId) {
        }

        @Override
        public void databaseCommitted() {
        }

        @Override
        public void promote() {
        }

        @Override
        public void finish() {
        }

        @Override
        public void abort() throws IOException {
            if (aborted) {
                return;
            }
            aborted = true;
            IOException firstFailure = null;
            for (int index = storedPaths.size() - 1; index >= 0; index--) {
                try {
                    storage.delete(storedPaths.get(index));
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
            }
            if (stagedPath != null) {
                try {
                    storage.delete(stagedPath.toString());
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        @Override
        public void close() {
        }
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

    public record ImportLimits(long maxSourceBytes) {

        public ImportLimits {
            if (maxSourceBytes <= 0) {
                throw new IllegalArgumentException("maxSourceBytes must be positive");
            }
        }

        public static ImportLimits defaults() {
            return new ImportLimits(DEFAULT_MAX_SOURCE_BYTES);
        }
    }

    private interface ImportWork {

        BookImportResult run();
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private long bytesRead;

        private LimitedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                bytesRead++;
                ensureWithinLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
                ensureWithinLimit();
            }
            return read;
        }

        private void ensureWithinLimit() {
            if (bytesRead > maxBytes) {
                throw new EpubImportException(
                        EPUB_SOURCE_TOO_LARGE,
                        "EPUB upload exceeds " + maxBytes + " bytes");
            }
        }
    }

    interface AtomicFileOperations {

        void moveAtomically(Path source, Path target) throws IOException;

        void replaceAtomically(Path source, Path target) throws IOException;
    }

    public static final class FileBookStorage implements BookStorage {

        private static final String STAGING_DIRECTORY_NAME = "staging";
        private static final String JOURNAL_FILENAME = "journal.properties";
        private static final String LOCK_FILENAME = "process.lock";
        private static final String STATE_PREPARED = "PREPARED";
        private static final String STATE_DB_COMMITTED = "DB_COMMITTED";
        private static final String STATE_PROMOTING = "PROMOTING";
        private static final String STATE_PROMOTED = "PROMOTED";
        private static final String STATE_ABORTED = "ABORTED";

        private static final class DefaultAtomicFileOperations implements AtomicFileOperations {

            @Override
            public void moveAtomically(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void replaceAtomically(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private final Path storageRoot;
        private final AtomicFileOperations fileOperations;

        public FileBookStorage(Path storageRoot) {
            this(storageRoot, new DefaultAtomicFileOperations());
        }

        FileBookStorage(Path storageRoot, AtomicFileOperations fileOperations) {
            this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot must not be null")
                    .toAbsolutePath().normalize();
            this.fileOperations = Objects.requireNonNull(fileOperations,
                    "fileOperations must not be null");
        }

        @Override
        public ImportWorkspace beginImport() throws IOException {
            return new FileImportWorkspace(this);
        }

        @Override
        public void reconcile(BookRepository repository) throws IOException {
            Path stagingRoot = storageRoot.resolve(STAGING_DIRECTORY_NAME).normalize();
            if (!Files.isDirectory(stagingRoot)) {
                return;
            }
            List<Path> workspaces;
            try (Stream<Path> paths = Files.list(stagingRoot)) {
                workspaces = paths.filter(Files::isDirectory).toList();
            }
            for (Path workspace : workspaces) {
                reconcileWorkspace(workspace, repository);
            }
        }

        @Override
        public StagedSource stage(InputStream source) throws IOException {
            Files.createDirectories(storageRoot);
            Path staged = Files.createTempFile(storageRoot, "upload-", ".epub");
            return stageToPath(source, staged);
        }

        private StagedSource stageToPath(InputStream source, Path staged) throws IOException {
            try {
                MessageDigest digest = sha256Digest();
                try (OutputStream output = Files.newOutputStream(
                        staged, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
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
        public String storeSource(Path stagedSource, String originalFilename, String sourceSha256)
                throws IOException {
            Path target = sourceTarget(originalFilename, sourceSha256);
            Path targetDirectory = target.getParent();
            Files.createDirectories(targetDirectory);
            move(stagedSource, target);
            return target.toString();
        }

        @Override
        public String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text)
                throws IOException {
            Path target = chapterTarget(bookId, bookVersionId, chapterNumber);
            Path directory = target.getParent();
            Files.createDirectories(directory);
            try {
                Files.writeString(target, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
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

        private Path sourceTarget(String originalFilename, String sourceSha256) {
            Path targetDirectory = storageRoot.resolve("sources").normalize();
            String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
            Path target = targetDirectory.resolve(
                    sourceSha256 + "-" + uniqueSuffix + "-" + sanitizeFilename(originalFilename)).normalize();
            ensureWithinRoot(target);
            return target;
        }

        private Path chapterTarget(long bookId, long bookVersionId, int chapterNumber) {
            Path directory = storageRoot.resolve("books").resolve(Long.toString(bookId))
                    .resolve("versions").resolve(Long.toString(bookVersionId)).resolve("chapters").normalize();
            Path target = directory.resolve(chapterNumber + ".txt").normalize();
            ensureWithinRoot(target);
            return target;
        }

        private void move(Path source, Path target) throws IOException {
            try {
                fileOperations.moveAtomically(source, target);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new EpubImportException(
                        EPUB_IMPORT_RECOVERY_UNAVAILABLE,
                        "Atomic EPUB file promotion is not supported by the storage filesystem", exception);
            }
        }

        private void replace(Path source, Path target) throws IOException {
            try {
                fileOperations.replaceAtomically(source, target);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new EpubImportException(
                        EPUB_IMPORT_RECOVERY_UNAVAILABLE,
                        "Atomic EPUB journal replacement is not supported by the storage filesystem", exception);
            }
        }

        private void reconcileWorkspace(Path workspaceDirectory, BookRepository repository)
                throws IOException {
            Path normalizedWorkspace = workspaceDirectory.toAbsolutePath().normalize();
            Path stagingRoot = storageRoot.resolve(STAGING_DIRECTORY_NAME).normalize();
            if (!normalizedWorkspace.getParent().equals(stagingRoot)
                    || !normalizedWorkspace.getFileName().toString().startsWith("import-")) {
                return;
            }

            ProcessLock processLock = tryAcquireProcessLock(
                    normalizedWorkspace.resolve(LOCK_FILENAME));
            if (processLock == null) {
                return;
            }

            boolean removeWorkspace = false;
            try {
                Path journalPath = normalizedWorkspace.resolve(JOURNAL_FILENAME);
                if (!Files.isRegularFile(journalPath)) {
                    throw recoveryFailed(
                            "EPUB import journal is missing; staging evidence was retained: " + journalPath);
                }

                Properties journal;
                try {
                    journal = loadJournal(journalPath);
                } catch (IOException | RuntimeException exception) {
                    throw recoveryFailed(
                            "Unable to read EPUB import journal; staging evidence was retained: "
                                    + journalPath,
                            exception);
                }
                String formatVersion = requiredJournalProperty(journal, "formatVersion");
                if (!"1".equals(formatVersion)) {
                    throw recoveryFailed("Unsupported EPUB import journal format: " + formatVersion);
                }
                String state = requiredJournalProperty(journal, "state");
                if (!Set.of(STATE_PREPARED, STATE_DB_COMMITTED, STATE_PROMOTING,
                        STATE_PROMOTED, STATE_ABORTED).contains(state)) {
                    throw recoveryFailed("Unknown EPUB import journal state: " + state);
                }
                long bookId = parseLong(journal, "bookId");
                long bookVersionId = parseLong(journal, "bookVersionId");
                if (bookId < 0 || bookVersionId < 0) {
                    throw recoveryFailed("EPUB import journal contains a negative identifier");
                }
                List<Mapping> mappings = readMappings(journal, normalizedWorkspace);
                String sourceSha256 = journal.getProperty("sourceSha256", "").trim();
                if (!sourceSha256.isEmpty() && !sourceSha256.matches("[0-9a-fA-F]{64}")) {
                    throw recoveryFailed("Invalid EPUB import journal source SHA-256");
                }
                if (!mappings.isEmpty() && sourceSha256.isEmpty()) {
                    throw recoveryFailed("EPUB import journal has mappings but no source SHA-256");
                }
                if (!mappings.isEmpty()
                        && !sourceSha256.equalsIgnoreCase(mappings.get(0).expectedSha256())) {
                    throw recoveryFailed("EPUB import journal source mapping hash does not match source SHA-256");
                }
                if ((STATE_DB_COMMITTED.equals(state) || STATE_PROMOTING.equals(state)
                        || STATE_PROMOTED.equals(state)) && bookVersionId <= 0) {
                    throw recoveryFailed("Committed EPUB import journal has no book version ID");
                }
                if (bookVersionId > 0 && mappings.isEmpty()) {
                    throw recoveryFailed("Committed EPUB import journal has no file mappings");
                }
                if (bookVersionId > 0 && bookId <= 0) {
                    throw recoveryFailed("Committed EPUB import journal has no book ID");
                }
                validateChapterMappingTargets(mappings, bookId, bookVersionId);

                Optional<StoredBookVersion> storedVersion = sourceSha256.matches(
                        "[0-9a-fA-F]{64}") && bookVersionId > 0
                        ? repository.findBySourceSha256(sourceSha256)
                        : Optional.empty();
                boolean ownsStoredVersion = storedVersion.isPresent()
                        && storedVersion.get().bookId() == bookId
                        && storedVersion.get().bookVersionId() == bookVersionId
                        && sourceSha256.equalsIgnoreCase(storedVersion.get().sourceSha256())
                        && sourceMappingMatches(mappings, storedVersion.get());
                if (ownsStoredVersion
                        && mappings.size() != (long) storedVersion.get().chapterCount() + 1) {
                    throw recoveryFailed("EPUB import journal file mapping count does not match the database");
                }
                if (!ownsStoredVersion) {
                    deleteFinalMappings(mappings);
                } else {
                    promoteMappings(mappings);
                    journal.setProperty("state", STATE_PROMOTED);
                    writeJournalFile(journalPath, journal);
                }
                removeWorkspace = true;
            } finally {
                processLock.close();
            }
            if (removeWorkspace) {
                deleteRecursively(normalizedWorkspace);
            }
        }

        private Properties loadJournal(Path journalPath) throws IOException {
            Properties journal = new Properties();
            try (InputStream input = Files.newInputStream(journalPath)) {
                journal.load(input);
            }
            return journal;
        }

        private List<Mapping> readMappings(Properties journal, Path workspaceDirectory)
                throws IOException {
            int count;
            try {
                count = Integer.parseInt(requiredJournalProperty(journal, "mapping.count"));
            } catch (NumberFormatException exception) {
                throw recoveryFailed("Invalid EPUB import journal mapping count", exception);
            }
            if (count < 0 || count > 100_000) {
                throw recoveryFailed("Invalid EPUB import journal mapping count");
            }
            List<Mapping> mappings = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String stagingPath = journal.getProperty("mapping." + index + ".staging");
                String finalPath = journal.getProperty("mapping." + index + ".final");
                String sizeValue = journal.getProperty("mapping." + index + ".size");
                String expectedSha256 = journal.getProperty("mapping." + index + ".sha256");
                if (stagingPath == null || finalPath == null || sizeValue == null || expectedSha256 == null) {
                    throw recoveryFailed("Incomplete EPUB import journal mapping");
                }
                long expectedSize;
                try {
                    expectedSize = Long.parseLong(sizeValue);
                } catch (NumberFormatException exception) {
                    throw recoveryFailed("Invalid EPUB import journal mapping size", exception);
                }
                if (expectedSize < 0 || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
                    throw recoveryFailed("Invalid EPUB import journal mapping metadata");
                }
                try {
                    Path staging = Path.of(stagingPath).toAbsolutePath().normalize();
                    Path target = Path.of(finalPath).toAbsolutePath().normalize();
                    ensureWithinWorkspace(staging, workspaceDirectory);
                    ensureFileWithinRoot(target);
                    mappings.add(new Mapping(
                            staging, target, expectedSize, expectedSha256.toLowerCase(Locale.ROOT)));
                } catch (IOException | RuntimeException exception) {
                    if (exception instanceof EpubImportException epubImportException) {
                        throw epubImportException;
                    }
                    throw recoveryFailed("Invalid EPUB import journal mapping path", exception);
                }
            }
            return List.copyOf(mappings);
        }

        private void validateChapterMappingTargets(List<Mapping> mappings, long bookId, long bookVersionId) {
            if (bookVersionId <= 0) {
                return;
            }
            for (int index = 1; index < mappings.size(); index++) {
                Path expected = chapterTarget(bookId, bookVersionId, index);
                if (!expected.equals(mappings.get(index).finalPath())) {
                    throw recoveryFailed(
                            "EPUB import journal chapter mapping does not match its book version: "
                                    + mappings.get(index).finalPath());
                }
            }
        }

        private long parseLong(Properties journal, String key) throws IOException {
            String value = requiredJournalProperty(journal, key);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw recoveryFailed("Invalid EPUB import journal value: " + key, exception);
            }
        }

        private String requiredJournalProperty(Properties journal, String key) {
            String value = journal.getProperty(key);
            if (value == null || value.isBlank()) {
                throw recoveryFailed("Incomplete EPUB import journal; missing " + key);
            }
            return value.trim();
        }

        private boolean sourceMappingMatches(List<Mapping> mappings, StoredBookVersion version) {
            if (mappings.isEmpty()) {
                return false;
            }
            try {
                Path storedSource = Path.of(version.sourceFilePath()).toAbsolutePath().normalize();
                return mappings.get(0).finalPath().equals(storedSource);
            } catch (RuntimeException exception) {
                return false;
            }
        }

        private void promoteMappings(List<Mapping> mappings) throws IOException {
            for (Mapping mapping : mappings) {
                ensureFileWithinRoot(mapping.finalPath());
                boolean stagingExists = Files.exists(mapping.stagingPath());
                boolean finalExists = Files.exists(mapping.finalPath());
                if (stagingExists) {
                    verifyMappingFile(mapping.stagingPath(), mapping, "staging");
                    if (finalExists) {
                        verifyMappingFile(mapping.finalPath(), mapping, "final");
                        try {
                            Files.deleteIfExists(mapping.stagingPath());
                        } catch (IOException exception) {
                            throw recoveryFailed(
                                    "Unable to remove the verified EPUB staging file: "
                                            + mapping.stagingPath(), exception);
                        }
                    } else {
                        try {
                            Files.createDirectories(mapping.finalPath().getParent());
                            move(mapping.stagingPath(), mapping.finalPath());
                        } catch (EpubImportException exception) {
                            throw exception;
                        } catch (IOException exception) {
                            throw recoveryFailed(
                                    "Unable to atomically promote EPUB file: " + mapping.stagingPath(),
                                    exception);
                        }
                        verifyMappingFile(mapping.finalPath(), mapping, "final");
                    }
                } else if (finalExists) {
                    verifyMappingFile(mapping.finalPath(), mapping, "final");
                } else {
                    throw recoveryFailed(
                            "EPUB import mapping has neither a staging file nor a final file: "
                                    + mapping.stagingPath());
                }
            }
        }

        private void verifyMappingFile(Path path, Mapping mapping, String location) {
            if (!Files.isRegularFile(path)) {
                throw recoveryFailed(
                        "EPUB import " + location + " file is missing or not a regular file: " + path);
            }
            try {
                long actualSize = Files.size(path);
                if (actualSize != mapping.expectedSize()) {
                    throw recoveryFailed(
                            "EPUB import " + location + " file size does not match its journal: " + path);
                }
                String actualSha256 = sha256File(path);
                if (!mapping.expectedSha256().equalsIgnoreCase(actualSha256)) {
                    throw recoveryFailed(
                            "EPUB import " + location + " file SHA-256 does not match its journal: " + path);
                }
            } catch (IOException exception) {
                throw recoveryFailed(
                        "Unable to verify EPUB import " + location + " file: " + path, exception);
            }
        }

        private void deleteFinalMappings(List<Mapping> mappings) throws IOException {
            IOException firstFailure = null;
            for (Mapping mapping : mappings) {
                try {
                    ensureFileWithinRoot(mapping.finalPath());
                    Files.deleteIfExists(mapping.finalPath());
                } catch (IOException | RuntimeException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception instanceof IOException ioException
                                ? ioException
                                : new IOException(exception);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        private void ensureFileWithinRoot(Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(storageRoot) || normalized.equals(storageRoot)) {
                throw new IllegalArgumentException("Storage path escapes storage root: " + path);
            }
        }

        private void ensureWithinWorkspace(Path path, Path workspaceDirectory) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            Path workspace = workspaceDirectory.toAbsolutePath().normalize();
            if (!normalized.startsWith(workspace) || normalized.equals(workspace)) {
                throw new IOException("EPUB import journal staging path escapes its workspace: " + path);
            }
        }

        private ProcessLock tryAcquireProcessLock(Path lockPath) throws IOException {
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new ProcessLock(channel, lock);
            } catch (OverlappingFileLockException exception) {
                channel.close();
                return null;
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        }

        private void writeJournalFile(Path journalPath, Properties properties) throws IOException {
            Path temporary = journalPath.resolveSibling(JOURNAL_FILENAME + ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(
                        temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    properties.store(output, "audiobook-factory EPUB import journal");
                }
                forceFile(temporary);
                replace(temporary, journalPath);
                forceFile(journalPath);
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        }

        private void forceFile(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        private void deleteRecursively(Path directory) throws IOException {
            if (!Files.exists(directory)) {
                return;
            }
            List<Path> paths;
            try (Stream<Path> stream = Files.walk(directory)) {
                paths = stream.sorted(Comparator.reverseOrder()).toList();
            }
            IOException firstFailure = null;
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        private String sha256File(Path path) throws IOException {
            MessageDigest digest = sha256Digest();
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }

        private EpubImportException recoveryFailed(String message) {
            return new EpubImportException(EPUB_IMPORT_RECOVERY_FAILED, message);
        }

        private EpubImportException recoveryFailed(String message, Throwable cause) {
            return new EpubImportException(EPUB_IMPORT_RECOVERY_FAILED, message, cause);
        }

        private record Mapping(Path stagingPath, Path finalPath, long expectedSize, String expectedSha256) {
        }

        private static final class ProcessLock implements AutoCloseable {

            private final FileChannel channel;
            private final FileLock lock;
            private boolean closed;

            private ProcessLock(FileChannel channel, FileLock lock) {
                this.channel = channel;
                this.lock = lock;
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                IOException firstFailure = null;
                try {
                    lock.release();
                } catch (IOException exception) {
                    firstFailure = exception;
                }
                try {
                    channel.close();
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            }
        }

        private static final class FileImportWorkspace implements ImportWorkspace {

            private final FileBookStorage storage;
            private final Path workspaceDirectory;
            private final Path journalPath;
            private final List<Mapping> mappings = new ArrayList<>();
            private ProcessLock processLock;
            private String sourceSha256;
            private long bookId;
            private long bookVersionId;
            private String state = STATE_PREPARED;
            private boolean databaseCommitted;
            private boolean aborted;

            private FileImportWorkspace(FileBookStorage storage) throws IOException {
                this.storage = storage;
                Path stagingRoot = storage.storageRoot.resolve(STAGING_DIRECTORY_NAME).normalize();
                Files.createDirectories(stagingRoot);
                this.workspaceDirectory = stagingRoot.resolve(
                        "import-" + UUID.randomUUID().toString().replace("-", "")).normalize();
                storage.ensureWithinRoot(workspaceDirectory);
                Files.createDirectories(workspaceDirectory);
                this.journalPath = workspaceDirectory.resolve(JOURNAL_FILENAME);
                this.processLock = storage.tryAcquireProcessLock(
                        workspaceDirectory.resolve(LOCK_FILENAME));
                if (processLock == null) {
                    throw new IOException("Unable to lock EPUB import workspace");
                }
                try {
                    persistJournal();
                } catch (IOException | RuntimeException exception) {
                    processLock.close();
                    processLock = null;
                    storage.deleteRecursively(workspaceDirectory);
                    throw exception;
                }
            }

            @Override
            public StagedSource stage(InputStream source) throws IOException {
                Path stagedPath = workspaceDirectory.resolve("upload.epub");
                StagedSource staged = storage.stageToPath(source, stagedPath);
                sourceSha256 = staged.sha256();
                persistJournal();
                return staged;
            }

            @Override
            public String storeSource(Path stagedSource, String originalFilename, String sourceSha256)
                    throws IOException {
                Path sourcePath = storage.sourceTarget(originalFilename, sourceSha256);
                String actualSha256 = storage.sha256File(stagedSource);
                if (!actualSha256.equalsIgnoreCase(sourceSha256)) {
                    throw new IOException("EPUB source SHA-256 changed while it was staged");
                }
                registerMapping(stagedSource, sourcePath, Files.size(stagedSource), actualSha256);
                return sourcePath.toString();
            }

            @Override
            public String storeChapterText(long bookId, long bookVersionId, int chapterNumber, String text)
                    throws IOException {
                Path stagedPath = workspaceDirectory.resolve("chapter-" + chapterNumber + ".txt");
                Path finalPath = storage.chapterTarget(bookId, bookVersionId, chapterNumber);
                byte[] encodedText = text.getBytes(StandardCharsets.UTF_8);
                registerMapping(stagedPath, finalPath, encodedText.length, BookHashing.sha256(encodedText));
                Files.write(stagedPath, encodedText,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return finalPath.toString();
            }

            @Override
            public void recordBookVersion(long bookId, long bookVersionId) throws IOException {
                if (bookId <= 0 || bookVersionId <= 0) {
                    throw new IllegalArgumentException("book and book version ids must be positive");
                }
                this.bookId = bookId;
                this.bookVersionId = bookVersionId;
                persistJournal();
            }

            @Override
            public void databaseCommitted() throws IOException {
                if (bookVersionId <= 0) {
                    throw new IllegalStateException("EPUB import has no recorded book version");
                }
                databaseCommitted = true;
                state = STATE_DB_COMMITTED;
                persistJournal();
            }

            @Override
            public void promote() throws IOException {
                if (!databaseCommitted) {
                    throw new IllegalStateException("EPUB import database transaction is not committed");
                }
                state = STATE_PROMOTING;
                persistJournal();
                storage.promoteMappings(mappings);
                state = STATE_PROMOTED;
                persistJournal();
            }

            @Override
            public void finish() throws IOException {
                close();
                storage.deleteRecursively(workspaceDirectory);
            }

            @Override
            public void abort() throws IOException {
                if (aborted || databaseCommitted) {
                    return;
                }
                aborted = true;
                IOException firstFailure = null;
                try {
                    state = STATE_ABORTED;
                    persistJournal();
                } catch (IOException | RuntimeException exception) {
                    firstFailure = exception instanceof IOException ioException
                            ? ioException
                            : new IOException(exception);
                }
                try {
                    storage.deleteFinalMappings(mappings);
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
                try {
                    close();
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
                if (firstFailure == null) {
                    try {
                        storage.deleteRecursively(workspaceDirectory);
                    } catch (IOException exception) {
                        firstFailure = exception;
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            }

            @Override
            public void close() throws IOException {
                if (processLock != null) {
                    ProcessLock lock = processLock;
                    processLock = null;
                    lock.close();
                }
            }

            private void registerMapping(Path stagingPath, Path finalPath,
                                         long expectedSize, String expectedSha256) throws IOException {
                Path normalizedStaging = stagingPath.toAbsolutePath().normalize();
                Path normalizedFinal = finalPath.toAbsolutePath().normalize();
                storage.ensureWithinRoot(normalizedStaging);
                storage.ensureWithinRoot(normalizedFinal);
                storage.ensureWithinWorkspace(normalizedStaging, workspaceDirectory);
                storage.ensureFileWithinRoot(normalizedFinal);
                mappings.add(new Mapping(normalizedStaging, normalizedFinal, expectedSize, expectedSha256));
                persistJournal();
            }

            private void persistJournal() throws IOException {
                Properties journal = new Properties();
                journal.setProperty("formatVersion", "1");
                journal.setProperty("state", state);
                if (sourceSha256 != null) {
                    journal.setProperty("sourceSha256", sourceSha256);
                }
                journal.setProperty("bookId", Long.toString(bookId));
                journal.setProperty("bookVersionId", Long.toString(bookVersionId));
                journal.setProperty("mapping.count", Integer.toString(mappings.size()));
                for (int index = 0; index < mappings.size(); index++) {
                    Mapping mapping = mappings.get(index);
                    journal.setProperty("mapping." + index + ".staging",
                            mapping.stagingPath().toString());
                    journal.setProperty("mapping." + index + ".final",
                            mapping.finalPath().toString());
                    journal.setProperty("mapping." + index + ".size",
                            Long.toString(mapping.expectedSize()));
                    journal.setProperty("mapping." + index + ".sha256", mapping.expectedSha256());
                }
                storage.writeJournalFile(journalPath, journal);
            }
        }

        private String sanitizeFilename(String originalFilename) {
            String leaf = filenameLeaf(originalFilename);
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
        public void createGenerationJob(long chapterId, int segmentIndex, String segmentText,
                                        String textSha256, String presetSnapshot) {
            jdbcTemplate.update(
                    "INSERT INTO generation_job (chapter_id, segment_index, segment_text, text_sha256, preset_snapshot) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
                    chapterId, segmentIndex, segmentText, textSha256, presetSnapshot);
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
