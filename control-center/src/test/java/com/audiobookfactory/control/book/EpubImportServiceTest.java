package com.audiobookfactory.control.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        Path maliciousEpub = writeZip(Map.of(
                "../escape.xhtml", "should never be read",
                "META-INF/container.xml", containerXml("OPS/package.opf")));

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
        Path maliciousEpub = writeZip(Map.of("META-INF/container.xml", container));

        assertThatThrownBy(() -> new EpubChapterExtractor().extract(maliciousEpub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCTYPE");
    }

    private InputStream fixture() throws IOException {
        return new ByteArrayInputStream(fixtureBytes());
    }

    private byte[] fixtureBytes() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", resource("META-INF/container.xml"));
        entries.put("OPS/package.opf", resource("OPS/package.opf"));
        entries.put("OPS/text/first.xhtml", resource("OPS/text/first.xhtml"));
        entries.put("OPS/text/second.xhtml", resource("OPS/text/second.xhtml"));
        return zipBytes(entries);
    }

    private Path writeZip(Map<String, String> entries) throws IOException {
        Path epub = Files.createTempFile(storageRoot, "fixture-", ".epub");
        Files.write(epub, zipBytes(entries));
        return epub;
    }

    private byte[] zipBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
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

    private String containerXml(String opfPath) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">"
                + "<rootfiles><rootfile full-path=\"" + opfPath
                + "\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
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
            long id = nextVersionId++;
            versions.add(new EpubImportService.StoredBookVersion(
                    bookId, id, chapterCount, sourceFilePath, sourceSha256));
            return id;
        }

        @Override
        public long createChapter(long bookVersionId, int chapterNumber, String title,
                                  String textPath, String textSha256) {
            long id = nextChapterId++;
            chapters.add(new ChapterRow(id, bookVersionId, chapterNumber, title, textPath, textSha256));
            return id;
        }

        @Override
        public void createGenerationJob(long chapterId, int segmentIndex, String segmentText,
                                        String textSha256) {
            jobs.add(new JobRow(chapterId, segmentIndex, segmentText, textSha256));
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

        private record BookRow(long id, String title) {
        }

        private record ChapterRow(long id, long bookVersionId, int chapterNumber, String title,
                                  String textPath, String textSha256) {
        }

        private record JobRow(long chapterId, int segmentIndex, String segmentText, String textSha256) {
        }
    }
}
