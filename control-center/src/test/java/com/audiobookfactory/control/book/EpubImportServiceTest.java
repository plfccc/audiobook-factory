package com.audiobookfactory.control.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private Path writeZip(Map<String, String> entries) throws IOException {
        return writeZip(entries, true);
    }

    private Path writeZip(Map<String, String> entries, boolean storeMimetype) throws IOException {
        Path epub = Files.createTempFile(storageRoot, "fixture-", ".epub");
        Files.write(epub, zipBytes(entries, storeMimetype));
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
