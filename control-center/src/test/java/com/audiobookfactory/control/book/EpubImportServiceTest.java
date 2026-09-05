package com.audiobookfactory.control.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubImportServiceTest {

    @TempDir
    Path storageRoot;

    private InMemoryBookRepository repository;
    private EpubImportService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookRepository();
        service = new EpubImportService(
                repository,
                storageRoot,
                new EpubChapterExtractor(),
                new TextSegmenter(),
                new SegmentationPolicy(2, 8, 12, "v1"));
    }

    @Test
    void importsSpineOrderAndKeepsChapterTitles() throws Exception {
        BookImportResult result = service.importBook(fixture(), "sample.epub");

        assertThat(result.chapterCount()).isEqualTo(2);
        assertThat(repository.findChapter(0).title()).isEqualTo("第一章");
        assertThat(repository.findChapter(1).title()).isEqualTo("第二章");
        assertThat(repository.findChapter(0).chapterNumber()).isEqualTo(1);
        assertThat(repository.findChapter(1).chapterNumber()).isEqualTo(2);
        String firstChapterText = Files.readString(Path.of(repository.findChapter(0).textPath()));
        assertThat(firstChapterText)
                .contains("第一段内容。")
                .doesNotContain("[1]")
                .doesNotContain("脚注不应进入正文")
                .doesNotStartWith("第一章第一章");
        assertThat(result.sourceSha256()).isEqualTo(sha256(fixtureBytes()));
    }

    @Test
    void keepsParagraphBoundariesInExtractedChapterText() throws Exception {
        Path epub = writeZip(fixtureEntries());

        List<ChapterDraft> chapters = new EpubChapterExtractor().extract(epub);

        assertThat(chapters.get(0).text()).contains("第一段内容。\n正文带脚注链接。");
    }

    @Test
    void onlyRemovesAnOpeningDuplicateTitleNode() throws Exception {
        Map<String, String> entries = fixtureEntries();
        entries.put("OPS/text/first.xhtml", repeatedTitleXhtml());

        ChapterDraft chapter = new EpubChapterExtractor().extract(writeZip(entries)).get(0);

        assertThat(chapter.text()).isEqualTo("正文中的第一章保留。\n第一章");
    }

    @Test
    void extractorRejectsAFileWithTheWrongExtension() throws Exception {
        Path input = Files.createTempFile(storageRoot, "fixture-", ".txt");
        Files.write(input, fixtureBytes());

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(input))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_EPUB_EXTENSION"));
    }

    @Test
    void decodesXhtmlUsingItsUtf16BomAndXmlEncodingDeclaration() throws Exception {
        Map<String, byte[]> entries = fixtureEntriesAsBytes();
        String xhtml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>编码章节</title></head>"
                + "<body><h1>编码章节</h1><p>UTF-16 内容 Café。</p></body></html>";
        entries.put("OPS/text/first.xhtml", xhtml.getBytes(StandardCharsets.UTF_16));

        List<ChapterDraft> chapters = new EpubChapterExtractor().extract(writeRawZip(entries));

        assertThat(chapters.get(0).text()).contains("UTF-16 内容 Café。");
    }

    @Test
    void removesUncommittedImportStagingDuringReconciliation() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        workspace.stage(new ByteArrayInputStream(fixtureBytes()));
        workspace.close();

        new EpubImportService(repository, fileStorage).reconcileStaging();

        try (Stream<Path> files = Files.walk(storageRoot)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void promotesCommittedImportAfterPromotionWasInterrupted() throws Exception {
        byte[] sourceBytes = fixtureBytes();
        String sourceSha256 = sha256(sourceBytes);
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(sourceBytes));
        String sourcePath = workspace.storeSource(staged.path(), "sample.epub", sourceSha256);
        String chapterPath = workspace.storeChapterText(7, 8, 1, "recovered chapter");
        workspace.recordBookVersion(7, 8);
        repository.recordCommittedVersion(new EpubImportService.StoredBookVersion(
                7, 8, 1, sourcePath, sourceSha256));
        workspace.databaseCommitted();
        workspace.close();

        new EpubImportService(repository, fileStorage).reconcileStaging();

        assertThat(Files.exists(Path.of(sourcePath))).isTrue();
        assertThat(Files.readString(Path.of(chapterPath))).isEqualTo("recovered chapter");
        try (Stream<Path> files = Files.list(storageRoot.resolve("staging"))) {
            assertThat(files.filter(Files::isDirectory).toList()).isEmpty();
        }
    }

    @Test
    void keepsStagingWhenAtomicPromotionIsUnavailable() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(
                storageRoot,
                new EpubImportService.AtomicFileOperations() {
                    @Override
                    public void moveAtomically(Path source, Path target) throws IOException {
                        throw new AtomicMoveNotSupportedException(
                                source.toString(), target.toString(), "simulated content move failure");
                    }

                    @Override
                    public void replaceAtomically(Path source, Path target) throws IOException {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                });
        byte[] sourceBytes = fixtureBytes();
        String sourceSha256 = sha256(sourceBytes);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(sourceBytes));
        String sourcePath = workspace.storeSource(staged.path(), "sample.epub", sourceSha256);
        String chapterPath = workspace.storeChapterText(7, 8, 1, "recovered chapter");
        workspace.recordBookVersion(7, 8);
        repository.recordCommittedVersion(new EpubImportService.StoredBookVersion(
                7, 8, 1, sourcePath, sourceSha256));
        workspace.databaseCommitted();

        assertThatThrownBy(workspace::promote)
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_UNAVAILABLE));
        workspace.close();

        assertThat(Files.exists(staged.path())).isTrue();
        assertThat(Files.exists(Path.of(chapterPath))).isFalse();
        assertThat(Files.exists(Path.of(sourcePath))).isFalse();
    }

    @Test
    void keepsDurableJournalWhenAtomicJournalReplacementIsUnavailable() throws Exception {
        EpubImportService.AtomicFileOperations operations = new EpubImportService.AtomicFileOperations() {
            private int replacements;

            @Override
            public void moveAtomically(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void replaceAtomically(Path source, Path target) throws IOException {
                replacements++;
                if (replacements > 1) {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "simulated journal replacement failure");
                }
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        };
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(
                storageRoot, operations);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();

        assertThatThrownBy(() -> workspace.stage(new ByteArrayInputStream(fixtureBytes())))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_UNAVAILABLE));
        workspace.close();

        Path workspaceDirectory = onlyStagingWorkspace();
        assertThat(Files.isRegularFile(workspaceDirectory.resolve("journal.properties"))).isTrue();
        assertThat(Files.isRegularFile(workspaceDirectory.resolve("upload.epub"))).isTrue();
    }

    @Test
    void refusesToCompleteWhenOnlyFinalFileIsTruncated() throws Exception {
        byte[] sourceBytes = fixtureBytes();
        String sourceSha256 = sha256(sourceBytes);
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(sourceBytes));
        String sourcePath = workspace.storeSource(staged.path(), "sample.epub", sourceSha256);
        String chapterPath = workspace.storeChapterText(7, 8, 1, "recovered chapter");
        workspace.recordBookVersion(7, 8);
        repository.recordCommittedVersion(new EpubImportService.StoredBookVersion(
                7, 8, 1, sourcePath, sourceSha256));
        workspace.databaseCommitted();
        workspace.promote();
        workspace.close();
        Path workspaceDirectory = onlyStagingWorkspace();
        Files.writeString(Path.of(chapterPath), "truncated", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> new EpubImportService(repository, fileStorage).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));

        assertThat(Files.readString(Path.of(chapterPath))).isEqualTo("truncated");
        assertThat(Files.isRegularFile(workspaceDirectory.resolve("journal.properties"))).isTrue();
    }

    @Test
    void preservesStagingWhenJournalIsMissing() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(fixtureBytes()));
        workspace.close();
        Path workspaceDirectory = onlyStagingWorkspace();
        Files.delete(workspaceDirectory.resolve("journal.properties"));

        assertThatThrownBy(() -> new EpubImportService(repository, fileStorage).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));

        assertThat(Files.exists(staged.path())).isTrue();
        assertThat(Files.exists(workspaceDirectory)).isTrue();
    }

    @Test
    void upgradesLegacyV1JournalWithoutFileMetadataDuringReconciliation() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        PreparedImport prepared = prepareCommittedImport(fileStorage);
        prepared.workspace().close();

        Path journalPath = prepared.workspaceDirectory().resolve("journal.properties");
        Properties journal = readProperties(journalPath);
        journal.setProperty("formatVersion", "1");
        int mappingCount = Integer.parseInt(journal.getProperty("mapping.count"));
        for (int index = 0; index < mappingCount; index++) {
            journal.remove("mapping." + index + ".size");
            journal.remove("mapping." + index + ".sha256");
        }
        writeProperties(journalPath, journal);

        new EpubImportService(repository, new EpubImportService.FileBookStorage(storageRoot))
                .reconcileStaging();

        assertThat(Files.exists(Path.of(prepared.sourcePath()))).isTrue();
        assertThat(Files.readString(Path.of(prepared.chapterPath())))
                .isEqualTo("recovered chapter");
        assertThat(Files.exists(prepared.workspaceDirectory())).isFalse();
    }

    @Test
    void recoversAfterCommitJournalReplacementFailureOnRestart() throws Exception {
        EpubImportService.AtomicFileOperations operations = new EpubImportService.AtomicFileOperations() {
            private int replacements;

            @Override
            public void moveAtomically(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void replaceAtomically(Path source, Path target) throws IOException {
                replacements++;
                if (replacements > 5) {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(),
                            "simulated post-commit journal replacement failure");
                }
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        };
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(
                storageRoot, operations);
        byte[] sourceBytes = fixtureBytes();
        String sourceSha256 = sha256(sourceBytes);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(sourceBytes));
        String sourcePath = workspace.storeSource(staged.path(), "sample.epub", sourceSha256);
        String chapterPath = workspace.storeChapterText(7, 8, 1, "recovered chapter");
        workspace.recordBookVersion(7, 8);
        repository.recordCommittedVersion(new EpubImportService.StoredBookVersion(
                7, 8, 1, sourcePath, sourceSha256));

        assertThatThrownBy(workspace::databaseCommitted)
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_UNAVAILABLE));
        assertThat(readProperties(onlyStagingWorkspace().resolve("journal.properties"))
                .getProperty("formatVersion")).isEqualTo("2");
        workspace.close();

        new EpubImportService(repository, new EpubImportService.FileBookStorage(storageRoot))
                .reconcileStaging();

        assertThat(Files.exists(Path.of(sourcePath))).isTrue();
        assertThat(Files.readString(Path.of(chapterPath)))
                .isEqualTo("recovered chapter");
        try (Stream<Path> files = Files.list(storageRoot.resolve("staging"))) {
            assertThat(files.filter(Files::isDirectory).toList()).isEmpty();
        }
    }

    @Test
    void neverPromotesAnAbortedImportEvenWhenDatabaseMetadataMatches() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        PreparedImport prepared = prepareCommittedImport(fileStorage);
        prepared.workspace().close();

        Path journalPath = prepared.workspaceDirectory().resolve("journal.properties");
        Properties journal = readProperties(journalPath);
        journal.setProperty("state", "ABORTED");
        writeProperties(journalPath, journal);

        assertThatThrownBy(() -> new EpubImportService(repository,
                new EpubImportService.FileBookStorage(storageRoot)).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));

        assertThat(Files.exists(Path.of(prepared.sourcePath()))).isFalse();
        assertThat(Files.exists(Path.of(prepared.chapterPath()))).isFalse();
        assertThat(Files.exists(prepared.sourceStagingPath())).isTrue();
        assertThat(Files.exists(prepared.chapterStagingPath())).isTrue();
        assertThat(Files.exists(prepared.workspaceDirectory())).isTrue();
    }

    @Test
    void refusesFinalFileThatIsARegularFileSymlink() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        PreparedImport prepared = prepareCommittedImport(fileStorage);
        prepared.workspace().promote();
        prepared.workspace().close();

        Path finalPath = Path.of(prepared.chapterPath());
        Path symlinkTarget = storageRoot.resolve("symlink-target.txt");
        Files.writeString(symlinkTarget, "recovered chapter", StandardCharsets.UTF_8);
        Files.delete(finalPath);
        try {
            Files.createSymbolicLink(finalPath, symlinkTarget);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false,
                    "symbolic links are unavailable in this test environment: " + exception.getMessage());
        }

        assertThatThrownBy(() -> new EpubImportService(repository,
                new EpubImportService.FileBookStorage(storageRoot)).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));
        assertThat(Files.isSymbolicLink(finalPath)).isTrue();
        assertThat(Files.exists(prepared.workspaceDirectory())).isTrue();
    }

    @Test
    void refusesFinalDirectoryDuringReconciliation() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        PreparedImport prepared = prepareCommittedImport(fileStorage);
        prepared.workspace().promote();
        prepared.workspace().close();

        Path finalPath = Path.of(prepared.chapterPath());
        Files.delete(finalPath);
        Files.createDirectory(finalPath);

        assertThatThrownBy(() -> new EpubImportService(repository,
                new EpubImportService.FileBookStorage(storageRoot)).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));
        assertThat(Files.isDirectory(finalPath)).isTrue();
        assertThat(Files.exists(prepared.workspaceDirectory())).isTrue();
    }

    @Test
    void refusesSameSizeFinalContentTamperingDuringReconciliation() throws Exception {
        EpubImportService.FileBookStorage fileStorage = new EpubImportService.FileBookStorage(storageRoot);
        PreparedImport prepared = prepareCommittedImport(fileStorage);
        prepared.workspace().promote();
        prepared.workspace().close();

        Path finalPath = Path.of(prepared.chapterPath());
        Files.writeString(finalPath, "corrupted chapter", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        assertThat(Files.size(finalPath))
                .isEqualTo("recovered chapter".getBytes(StandardCharsets.UTF_8).length);

        assertThatThrownBy(() -> new EpubImportService(repository,
                new EpubImportService.FileBookStorage(storageRoot)).reconcileStaging())
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(EpubImportService.EPUB_IMPORT_RECOVERY_FAILED));
        assertThat(Files.readString(finalPath)).isEqualTo("corrupted chapter");
        assertThat(Files.exists(prepared.workspaceDirectory())).isTrue();
    }

    @Test
    void returnsExistingVersionForDuplicateSourceWithoutCreatingChaptersOrJobs() throws Exception {
        BookImportResult first = service.importBook(fixture(), "sample.epub");
        int chapterCountAfterFirstImport = repository.chapterCount();
        int jobCountAfterFirstImport = repository.jobCount();

        BookImportResult second = service.importBook(fixture(), "renamed-copy.epub");

        assertThat(second).isEqualTo(first);
        assertThat(repository.bookCount()).isEqualTo(1);
        assertThat(repository.versionCount()).isEqualTo(1);
        assertThat(repository.chapterCount()).isEqualTo(chapterCountAfterFirstImport);
        assertThat(repository.jobCount()).isEqualTo(jobCountAfterFirstImport);
        assertThat(repository.jobs()).allSatisfy(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.presetSnapshot()).isEqualTo("{}");
        });
    }

    @Test
    void returnsWinnerWhenGlobalSourceShaUniqueConstraintWinsRace() throws Exception {
        String sourceSha256 = sha256(fixtureBytes());
        repository.simulateUniqueConflict(new EpubImportService.StoredBookVersion(
                41,
                42,
                2,
                storageRoot.resolve("winner.epub").toString(),
                sourceSha256));

        BookImportResult result = service.importBook(fixture(), "sample.epub");

        assertThat(result).isEqualTo(new BookImportResult(41, 42, 2, sourceSha256));
        assertThat(repository.chapterCount()).isZero();
        assertThat(repository.jobCount()).isZero();
    }

    @Test
    void rejectsNonEpubUploadWithAnExplicitErrorCode() {
        assertThatThrownBy(() -> service.importBook(fixture(), "sample.txt"))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_EPUB_EXTENSION"));
    }

    @Test
    void rejectsEpubWhenMimetypeIsNotTheFirstStoredEntry() throws Exception {
        Map<String, String> entries = fixtureEntries();
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("META-INF/container.xml", entries.get("META-INF/container.xml"));
        reordered.put("mimetype", entries.get("mimetype"));
        reordered.put("OPS/package.opf", entries.get("OPS/package.opf"));
        reordered.put("OPS/text/first.xhtml", entries.get("OPS/text/first.xhtml"));
        reordered.put("OPS/text/second.xhtml", entries.get("OPS/text/second.xhtml"));
        Path epub = writeZip(reordered);

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_EPUB_MIMETYPE"));
    }

    @Test
    void rejectsCompressedMimetypeEntry() throws Exception {
        Path epub = writeZip(fixtureEntries(), false);

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_EPUB_MIMETYPE"));
    }

    @Test
    void rejectsRootfileWithUnexpectedMediaType() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", containerXmlWithMediaType("text/plain"));
        Path epub = writeZip(entries);

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_OPF_ROOTFILE_MEDIA_TYPE"));
    }

    @Test
    void rejectsUploadLargerThanConfiguredLimit() throws Exception {
        EpubImportService limitedService = new EpubImportService(
                repository,
                storageRoot,
                new EpubChapterExtractor(),
                new TextSegmenter(),
                new SegmentationPolicy(2, 8, 12, "v1"),
                new EpubImportService.ImportLimits(100));

        assertThatThrownBy(() -> limitedService.importBook(fixture(), "sample.epub"))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EPUB_SOURCE_TOO_LARGE"));
    }

    @Test
    void rejectsArchivesExceedingEntryCountLimit() throws Exception {
        Path epub = writeZip(fixtureEntries());
        EpubChapterExtractor extractor = new EpubChapterExtractor(
                new EpubChapterExtractor.ArchiveLimits(4, 1024 * 1024, 2 * 1024 * 1024));

        assertThatThrownBy(() -> extractor.extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EPUB_TOO_MANY_ENTRIES"));
    }

    @Test
    void rejectsArchivesExceedingSingleEntryLimit() throws Exception {
        Path epub = writeZip(fixtureEntries());
        EpubChapterExtractor extractor = new EpubChapterExtractor(
                new EpubChapterExtractor.ArchiveLimits(100, 10, 2 * 1024 * 1024));

        assertThatThrownBy(() -> extractor.extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EPUB_ENTRY_TOO_LARGE"));
    }

    @Test
    void rejectsArchivesExceedingTotalUncompressedLimit() throws Exception {
        Path epub = writeZip(fixtureEntries());
        EpubChapterExtractor extractor = new EpubChapterExtractor(
                new EpubChapterExtractor.ArchiveLimits(100, 1024 * 1024, 50));

        assertThatThrownBy(() -> extractor.extract(epub))
                .isInstanceOfSatisfying(EpubImportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EPUB_TOTAL_TOO_LARGE"));
    }

    @Test
    void storesSourceInsideStorageRootWhenFilenameContainsTraversal() throws Exception {
        service.importBook(fixture(), "..\\outside/../../book.epub");

        Path storedPath = Path.of(repository.versionAt(0).sourceFilePath()).toAbsolutePath().normalize();
        assertThat(storedPath).startsWith(storageRoot.toAbsolutePath().normalize());
        assertThat(storedPath.getFileName().toString()).doesNotContain("..");
        assertThat(Files.exists(storedPath)).isTrue();
    }

    @Test
    void rejectsZipEntryOutsideArchiveRoot() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("../escape.xhtml", "should never be read");
        entries.put("META-INF/container.xml", containerXml("OPS/package.opf"));
        Path maliciousEpub = writeZip(entries);

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(maliciousEpub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside EPUB root");
    }

    @Test
    void rejectsXmlExternalEntities() throws Exception {
        String container = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE container [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"OPS/package.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles><name>&xxe;</name></container>";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", container);
        Path maliciousEpub = writeZip(entries);

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(maliciousEpub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void removesAllFilesWhenRepositorySaveFails() throws Exception {
        repository.failOnCreateChapter();

        assertThatThrownBy(() -> service.importBook(fixture(), "sample.epub"))
                .isInstanceOf(IllegalStateException.class);

        try (Stream<Path> files = Files.walk(storageRoot)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void migrationAddsGlobalSourceShaUniqueConstraint() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V2__global_source_sha256_unique.sql")) {
            assertThat(input).as("global source SHA migration must exist").isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration)
                    .contains("ADD CONSTRAINT uq_book_version_source_sha256")
                    .contains("UNIQUE (source_file_sha256)");
        }
    }

    private InputStream fixture() throws IOException {
        return new ByteArrayInputStream(fixtureBytes());
    }

    private byte[] fixtureBytes() throws IOException {
        return zipBytes(fixtureEntries());
    }

    private Map<String, String> fixtureEntries() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", resource("META-INF/container.xml"));
        entries.put("OPS/package.opf", resource("OPS/package.opf"));
        entries.put("OPS/text/first.xhtml", resource("OPS/text/first.xhtml"));
        entries.put("OPS/text/second.xhtml", resource("OPS/text/second.xhtml"));
        return entries;
    }

    private Map<String, byte[]> fixtureEntriesAsBytes() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fixtureEntries().entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    private Path writeZip(Map<String, String> entries) throws IOException {
        return writeZip(entries, true);
    }

    private Path writeZip(Map<String, String> entries, boolean storeMimetype) throws IOException {
        Path epub = Files.createTempFile(storageRoot, "fixture-", ".epub");
        Files.write(epub, zipBytes(entries, storeMimetype));
        return epub;
    }

    private Path writeRawZip(Map<String, byte[]> entries) throws IOException {
        Path epub = Files.createTempFile(storageRoot, "fixture-", ".epub");
        Files.write(epub, rawZipBytes(entries));
        return epub;
    }

    private byte[] zipBytes(Map<String, String> entries) throws IOException {
        return zipBytes(entries, true);
    }

    private byte[] zipBytes(Map<String, String> entries, boolean storeMimetype) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                if (storeMimetype && "mimetype".equals(entry.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(bytes.length);
                    zipEntry.setCompressedSize(bytes.length);
                    zipEntry.setCrc(crc.getValue());
                }
                zip.putNextEntry(zipEntry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] rawZipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                byte[] bytes = entry.getValue();
                if ("mimetype".equals(entry.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(bytes.length);
                    zipEntry.setCompressedSize(bytes.length);
                    zipEntry.setCrc(crc.getValue());
                }
                zip.putNextEntry(zipEntry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private PreparedImport prepareCommittedImport(EpubImportService.FileBookStorage fileStorage)
            throws Exception {
        byte[] sourceBytes = fixtureBytes();
        String sourceSha256 = sha256(sourceBytes);
        EpubImportService.ImportWorkspace workspace = fileStorage.beginImport();
        EpubImportService.StagedSource staged = workspace.stage(new ByteArrayInputStream(sourceBytes));
        String sourcePath = workspace.storeSource(staged.path(), "sample.epub", sourceSha256);
        String chapterPath = workspace.storeChapterText(7, 8, 1, "recovered chapter");
        workspace.recordBookVersion(7, 8);
        repository.recordCommittedVersion(new EpubImportService.StoredBookVersion(
                7, 8, 1, sourcePath, sourceSha256));
        workspace.databaseCommitted();
        Path workspaceDirectory = onlyStagingWorkspace();
        return new PreparedImport(workspace, sourcePath, chapterPath, workspaceDirectory,
                staged.path(), workspaceDirectory.resolve("chapter-1.txt"));
    }

    private Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private void writeProperties(Path path, Properties properties) throws IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "test journal mutation");
        }
    }

    private Path onlyStagingWorkspace() throws IOException {
        try (Stream<Path> paths = Files.list(storageRoot.resolve("staging"))) {
            return paths.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    private record PreparedImport(EpubImportService.ImportWorkspace workspace,
                                  String sourcePath,
                                  String chapterPath,
                                  Path workspaceDirectory,
                                  Path sourceStagingPath,
                                  Path chapterStagingPath) {
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/epub/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String repeatedTitleXhtml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>第一章</title></head>"
                + "<body><h1>第一章</h1><p>第一章</p>"
                + "<p>正文中的第一章保留。</p><p>第一章</p></body></html>";
    }

    private String containerXml(String opfPath) {
        return containerXmlWithMediaType("application/oebps-package+xml", opfPath);
    }

    private String containerXmlWithMediaType(String mediaType) {
        return containerXmlWithMediaType(mediaType, "OPS/package.opf");
    }

    private String containerXmlWithMediaType(String mediaType, String opfPath) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">"
                + "<rootfiles><rootfile full-path=\"" + opfPath
                + "\" media-type=\"" + mediaType + "\"/></rootfiles></container>";
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static final class InMemoryBookRepository implements EpubImportService.BookRepository {

        private final List<BookRow> books = new ArrayList<>();
        private final List<EpubImportService.StoredBookVersion> versions = new ArrayList<>();
        private final List<ChapterRow> chapters = new ArrayList<>();
        private final List<JobRow> jobs = new ArrayList<>();
        private EpubImportService.StoredBookVersion concurrentWinner;
        private boolean failOnCreateChapter;
        private long nextBookId = 1;
        private long nextVersionId = 1;
        private long nextChapterId = 1;

        @Override
        public Optional<EpubImportService.StoredBookVersion> findBySourceSha256(String sourceSha256) {
            return versions.stream()
                    .filter(version -> version.sourceSha256().equals(sourceSha256))
                    .findFirst();
        }

        @Override
        public long createBook(String title) {
            long id = nextBookId++;
            books.add(new BookRow(id, title));
            return id;
        }

        @Override
        public long createBookVersion(long bookId, String sourceFilePath, String sourceSha256,
                                      String parserVersion, String segmentationRuleVersion, int chapterCount) {
            if (concurrentWinner != null) {
                versions.add(concurrentWinner);
                throw new DataIntegrityViolationException("uq_book_version_source_sha256");
            }
            long id = nextVersionId++;
            versions.add(new EpubImportService.StoredBookVersion(
                    bookId, id, chapterCount, sourceFilePath, sourceSha256));
            return id;
        }

        @Override
        public long createChapter(long bookVersionId, int chapterNumber, String title,
                                  String textPath, String textSha256) {
            if (failOnCreateChapter) {
                throw new IllegalStateException("simulated repository failure");
            }
            long id = nextChapterId++;
            chapters.add(new ChapterRow(id, bookVersionId, chapterNumber, title, textPath, textSha256, "PENDING"));
            return id;
        }

        @Override
        public void createGenerationJob(long chapterId, int segmentIndex, String segmentText,
                                        String textSha256, String presetSnapshot) {
            jobs.add(new JobRow(chapterId, segmentIndex, segmentText, textSha256, presetSnapshot, "PENDING"));
        }

        void simulateUniqueConflict(EpubImportService.StoredBookVersion winner) {
            concurrentWinner = winner;
        }

        void failOnCreateChapter() {
            failOnCreateChapter = true;
        }

        void recordCommittedVersion(EpubImportService.StoredBookVersion version) {
            versions.add(version);
        }

        ChapterRow findChapter(int index) {
            return chapters.get(index);
        }

        EpubImportService.StoredBookVersion versionAt(int index) {
            return versions.get(index);
        }

        int bookCount() {
            return books.size();
        }

        int versionCount() {
            return versions.size();
        }

        int chapterCount() {
            return chapters.size();
        }

        int jobCount() {
            return jobs.size();
        }

        List<JobRow> jobs() {
            return jobs;
        }

        private record BookRow(long id, String title) {
        }

        private record ChapterRow(long id, long bookVersionId, int chapterNumber, String title,
                                  String textPath, String textSha256, String status) {
        }

        private record JobRow(long chapterId, int segmentIndex, String segmentText, String textSha256,
                              String presetSnapshot, String status) {
        }
    }
}
