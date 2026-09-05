package com.audiobookfactory.control.book;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.w3c.dom.DocumentType;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class EpubChapterExtractor {

    public static final int DEFAULT_MAX_ZIP_ENTRIES = 10_000;
    public static final long DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;
    public static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;

    public static final String INVALID_EPUB_EXTENSION = "INVALID_EPUB_EXTENSION";
    public static final String INVALID_EPUB_MIMETYPE = "INVALID_EPUB_MIMETYPE";
    public static final String INVALID_OPF_ROOTFILE_MEDIA_TYPE = "INVALID_OPF_ROOTFILE_MEDIA_TYPE";
    public static final String EPUB_TOO_MANY_ENTRIES = "EPUB_TOO_MANY_ENTRIES";
    public static final String EPUB_ENTRY_TOO_LARGE = "EPUB_ENTRY_TOO_LARGE";
    public static final String EPUB_TOTAL_TOO_LARGE = "EPUB_TOTAL_TOO_LARGE";

    private static final String EPUB_MIMETYPE = "application/epub+zip";
    private static final String OPF_MEDIA_TYPE = "application/oebps-package+xml";
    private static final String CONTAINER_NAMESPACE =
            "urn:oasis:names:tc:opendocument:xmlns:container";
    private static final String OPF_NAMESPACE = "http://www.idpf.org/2007/opf";
    private static final String CJK_SPACE_PATTERN =
            "(?<=[\\p{IsHan}\\u3002\\uff01\\uff1f\\uff1b\\uff1a\\uff0c\\u3001"
                    + "\\u3009\\u300d\\u300f\\u3011\\u3015\\uff09])\\s+(?=[\\p{IsHan}])";
    private static final Set<String> NOISE_TAGS = Set.of(
            "nav", "script", "style", "header", "footer", "aside", "form");
    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "article", "blockquote", "body", "br", "caption", "dd", "div", "dl", "dt",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tbody",
            "td", "tfoot", "th", "thead", "tr", "ul");

    private final ArchiveLimits limits;

    public EpubChapterExtractor() {
        this(ArchiveLimits.defaults());
    }

    public EpubChapterExtractor(ArchiveLimits limits) {
        this.limits = limits == null ? ArchiveLimits.defaults() : limits;
    }

    public List<ChapterDraft> extract(Path epubPath) throws IOException {
        validateInput(epubPath);
        try (ZipFile epub = new ZipFile(epubPath.toFile())) {
            ArchiveIndex archive = indexEntries(epub);
            validateMimetype(epub, archive);
            validateArchiveContents(epub, archive);

            String containerPath = normalizeEntryPath("META-INF/container.xml");
            org.w3c.dom.Document container = parseXml(readEntry(epub, archive.entries(), containerPath));
            validateXmlRoot(container, "container", CONTAINER_NAMESPACE, "EPUB container.xml");

            org.w3c.dom.Element rootfile = findFirstElement(container, "rootfile");
            if (rootfile == null) {
                throw invalidStructure("EPUB container.xml does not contain a rootfile");
            }
            String rootfileMediaType = rootfile.getAttribute("media-type");
            if (!OPF_MEDIA_TYPE.equals(rootfileMediaType)) {
                throw new EpubImportException(
                        INVALID_OPF_ROOTFILE_MEDIA_TYPE,
                        "EPUB rootfile media-type must be " + OPF_MEDIA_TYPE);
            }
            String opfPath = resolveEntryPath("", requiredAttribute(rootfile, "full-path"));
            org.w3c.dom.Document opf = parseXml(readEntry(epub, archive.entries(), opfPath));
            validateXmlRoot(opf, "package", OPF_NAMESPACE, "EPUB OPF");

            Map<String, ManifestItem> manifest = readManifest(opf);
            org.w3c.dom.Element spine = findFirstElement(opf, "spine");
            if (spine == null) {
                throw invalidStructure("EPUB OPF does not contain a spine");
            }

            String opfDirectory = parentDirectory(opfPath);
            List<ChapterDraft> chapters = new ArrayList<>();
            int chapterNumber = 1;
            for (org.w3c.dom.Element itemRef : childElements(spine, "itemref")) {
                if ("no".equalsIgnoreCase(itemRef.getAttribute("linear"))) {
                    continue;
                }
                String idref = itemRef.getAttribute("idref").trim();
                if (idref.isEmpty()) {
                    throw invalidStructure("EPUB spine contains a blank idref");
                }
                ManifestItem item = manifest.get(idref);
                if (item == null) {
                    throw invalidStructure("EPUB spine references missing manifest item: " + idref);
                }
                if (item.navigation() || !isXhtml(item.mediaType())) {
                    continue;
                }
                String chapterPath = resolveEntryPath(opfDirectory, item.href());
                ChapterDraft chapter = extractChapter(
                        readEntry(epub, archive.entries(), chapterPath), chapterNumber);
                if (!chapter.text().isBlank()) {
                    chapters.add(chapter);
                    chapterNumber++;
                }
            }
            return List.copyOf(chapters);
        } catch (EpubImportException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new EpubImportException(
                    "INVALID_EPUB_ARCHIVE", "Unable to read EPUB ZIP archive", exception);
        } catch (IOException exception) {
            throw new EpubImportException(
                    "INVALID_EPUB_ARCHIVE", "Unable to read EPUB ZIP archive", exception);
        }
    }

    private void validateInput(Path epubPath) {
        if (epubPath == null) {
            throw new EpubImportException("INVALID_EPUB_INPUT", "EPUB path must not be null");
        }
        if (!Files.isRegularFile(epubPath)) {
            throw new EpubImportException(
                    "INVALID_EPUB_INPUT", "EPUB file does not exist: " + epubPath);
        }
        String filename = epubPath.getFileName() == null ? "" : epubPath.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new EpubImportException(
                    INVALID_EPUB_EXTENSION, "EPUB input must use the .epub extension");
        }
    }

    private ArchiveIndex indexEntries(ZipFile epub) {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        List<ZipEntry> orderedEntries = new ArrayList<>();
        Enumeration<? extends ZipEntry> enumeration = epub.entries();
        while (enumeration.hasMoreElements()) {
            if (orderedEntries.size() >= limits.maxEntries()) {
                throw new EpubImportException(
                        EPUB_TOO_MANY_ENTRIES,
                        "EPUB contains more than " + limits.maxEntries() + " ZIP entries");
            }
            ZipEntry entry = enumeration.nextElement();
            String normalized = normalizeEntryPath(entry.getName());
            if (entries.putIfAbsent(normalized, entry) != null) {
                throw invalidPath("EPUB contains duplicate entry: " + normalized);
            }
            orderedEntries.add(entry);
        }
        return new ArchiveIndex(orderedEntries, entries);
    }

    private void validateMimetype(ZipFile epub, ArchiveIndex archive) throws IOException {
        if (archive.orderedEntries().isEmpty()) {
            throw new EpubImportException(
                    INVALID_EPUB_MIMETYPE, "EPUB must start with a mimetype entry");
        }
        ZipEntry first = archive.orderedEntries().get(0);
        if (!"mimetype".equals(first.getName())
                || first.isDirectory()
                || first.getMethod() != ZipEntry.STORED) {
            throw new EpubImportException(
                    INVALID_EPUB_MIMETYPE,
                    "The first EPUB ZIP entry must be a STORED mimetype entry");
        }
        byte[] bytes = readEntry(epub, archive.entries(), "mimetype");
        if (!Arrays.equals(bytes, EPUB_MIMETYPE.getBytes(StandardCharsets.US_ASCII))) {
            throw new EpubImportException(
                    INVALID_EPUB_MIMETYPE,
                    "EPUB mimetype entry must contain exactly " + EPUB_MIMETYPE);
        }
    }

    private void validateArchiveContents(ZipFile epub, ArchiveIndex archive) throws IOException {
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        for (ZipEntry entry : archive.orderedEntries()) {
            if (entry.isDirectory()) {
                continue;
            }
            long declaredSize = entry.getSize();
            if (declaredSize > limits.maxEntryUncompressedBytes()) {
                throw new EpubImportException(
                        EPUB_ENTRY_TOO_LARGE,
                        "EPUB ZIP entry exceeds " + limits.maxEntryUncompressedBytes() + " bytes: "
                                + entry.getName());
            }
            long entryBytes = 0;
            try (InputStream input = epub.getInputStream(entry)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > limits.maxEntryUncompressedBytes()) {
                        throw new EpubImportException(
                                EPUB_ENTRY_TOO_LARGE,
                                "EPUB ZIP entry exceeds " + limits.maxEntryUncompressedBytes()
                                        + " bytes: " + entry.getName());
                    }
                    if (totalBytes > limits.maxTotalUncompressedBytes()) {
                        throw new EpubImportException(
                                EPUB_TOTAL_TOO_LARGE,
                                "EPUB uncompressed content exceeds "
                                        + limits.maxTotalUncompressedBytes() + " bytes");
                    }
                }
            }
        }
    }

    private byte[] readEntry(ZipFile epub, Map<String, ZipEntry> entries, String path) throws IOException {
        ZipEntry entry = entries.get(path);
        if (entry == null || entry.isDirectory()) {
            throw invalidStructure("EPUB entry not found: " + path);
        }
        long declaredSize = entry.getSize();
        if (declaredSize > limits.maxEntryUncompressedBytes()) {
            throw new EpubImportException(
                    EPUB_ENTRY_TOO_LARGE,
                    "EPUB ZIP entry exceeds " + limits.maxEntryUncompressedBytes() + " bytes: " + path);
        }
        try (InputStream input = epub.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     declaredSize > 0 && declaredSize <= Integer.MAX_VALUE ? (int) declaredSize : 8192)) {
            byte[] buffer = new byte[8192];
            long bytesRead = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                bytesRead += read;
                if (bytesRead > limits.maxEntryUncompressedBytes()) {
                    throw new EpubImportException(
                            EPUB_ENTRY_TOO_LARGE,
                            "EPUB ZIP entry exceeds " + limits.maxEntryUncompressedBytes() + " bytes: " + path);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private Map<String, ManifestItem> readManifest(org.w3c.dom.Document opf) {
        org.w3c.dom.Element manifest = findFirstElement(opf, "manifest");
        if (manifest == null) {
            throw invalidStructure("EPUB OPF does not contain a manifest");
        }
        Map<String, ManifestItem> items = new LinkedHashMap<>();
        for (org.w3c.dom.Element element : childElements(manifest, "item")) {
            String id = element.getAttribute("id").trim();
            String href = requiredAttribute(element, "href");
            String mediaType = requiredAttribute(element, "media-type");
            String properties = element.getAttribute("properties");
            if (id.isEmpty() || items.putIfAbsent(
                    id, new ManifestItem(href, mediaType, hasToken(properties, "nav"))) != null) {
                throw invalidStructure("EPUB manifest contains an invalid or duplicate id");
            }
        }
        return items;
    }

    private ChapterDraft extractChapter(byte[] xhtml, int chapterNumber) throws IOException {
        Document document = Jsoup.parse(
                new ByteArrayInputStream(xhtml),
                StandardCharsets.UTF_8.name(),
                "",
                Parser.xmlParser());
        Element body = document.selectFirst("body");
        if (body == null) {
            body = document;
        }

        removeNoise(body);
        Element heading = firstHeading(body);
        String title = heading == null
                ? normalizeText(document.title())
                : normalizeText(heading.text());
        if (heading != null) {
            heading.remove();
        }
        removeOpeningTitleElement(body, title);

        String text = structuredText(body);
        if (title.isEmpty()) {
            title = "Chapter " + chapterNumber;
        }
        return new ChapterDraft(title, text);
    }

    private void removeNoise(Element body) {
        List<Element> elements = new ArrayList<>(body.getAllElements());
        for (Element element : elements) {
            String tag = element.normalName();
            String epubType = element.attr("epub:type").toLowerCase(Locale.ROOT);
            String role = element.attr("role").toLowerCase(Locale.ROOT);
            if (NOISE_TAGS.contains(tag)
                    || hasToken(epubType, "footnote")
                    || hasToken(role, "doc-footnote")
                    || hasToken(role, "doc-noteref")
                    || isFootnoteContainer(element)
                    || isFootnoteLink(element)) {
                if (element != body) {
                    element.remove();
                }
            }
        }
    }

    private boolean isFootnoteLink(Element element) {
        if (!"a".equals(element.normalName())) {
            return false;
        }
        String epubType = element.attr("epub:type").toLowerCase(Locale.ROOT);
        String role = element.attr("role").toLowerCase(Locale.ROOT);
        String href = element.attr("href").toLowerCase(Locale.ROOT);
        return hasToken(epubType, "noteref")
                || hasToken(role, "doc-noteref")
                || href.startsWith("#fn")
                || href.contains("footnote");
    }

    private boolean isFootnoteContainer(Element element) {
        String id = element.id().toLowerCase(Locale.ROOT);
        String className = element.className().toLowerCase(Locale.ROOT);
        return id.contains("footnote") || id.matches("fn[-_]?[0-9]+")
                || className.contains("footnote") || className.contains("foot-notes");
    }

    private Element firstHeading(Element body) {
        for (Element element : body.getAllElements()) {
            if (element.normalName().matches("h[1-6]")) {
                return element;
            }
        }
        return null;
    }

    private void removeOpeningTitleElement(Element body, String title) {
        if (title.isEmpty()) {
            return;
        }
        removeOpeningTitleFromChildren(body, title);
    }

    private boolean removeOpeningTitleFromChildren(Element container, String title) {
        for (Node child : container.childNodes()) {
            if (child instanceof TextNode textNode) {
                if (!normalizeText(textNode.getWholeText()).isEmpty()) {
                    return false;
                }
                continue;
            }
            if (!(child instanceof Element element)) {
                continue;
            }
            String elementText = normalizeText(element.text());
            if (elementText.isEmpty()) {
                continue;
            }
            if (elementText.equals(title)) {
                element.remove();
                return true;
            }
            if (removeOpeningTitleFromChildren(element, title)) {
                return true;
            }
            return false;
        }
        return false;
    }

    private String structuredText(Element body) {
        StringBuilder raw = new StringBuilder();
        appendStructuredText(body, raw);
        List<String> lines = new ArrayList<>();
        for (String line : raw.toString().split("\\n", -1)) {
            String normalizedLine = normalizeInlineText(line);
            if (!normalizedLine.isBlank()) {
                lines.add(normalizedLine);
            }
        }
        return String.join("\n", lines);
    }

    private void appendStructuredText(Node node, StringBuilder output) {
        boolean block = node instanceof Element element && BLOCK_TAGS.contains(element.normalName());
        if (block) {
            ensureLineBreak(output);
        }
        if (node instanceof TextNode textNode) {
            output.append(textNode.getWholeText()
                    .replace('\u00a0', ' ')
                    .replaceAll("[\\t\\r\\n\\f\\u000b]+", " "));
        } else {
            for (Node child : node.childNodes()) {
                appendStructuredText(child, output);
            }
        }
        if (block) {
            ensureLineBreak(output);
        }
    }

    private void ensureLineBreak(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
    }

    private String normalizeText(String text) {
        return normalizeInlineText(text);
    }

    private String normalizeInlineText(String text) {
        return text == null ? "" : text
                .replace('\u00a0', ' ')
                .replaceAll("[\\t\\r\\n\\f\\u000b]+", " ")
                .replaceAll(" +", " ")
                .replaceAll(CJK_SPACE_PATTERN, "")
                .trim();
    }

    private org.w3c.dom.Document parseXml(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new InputSource(new ByteArrayInputStream(new byte[0])));
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException exception) {
                }

                @Override
                public void error(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            org.w3c.dom.Document document = builder.parse(new ByteArrayInputStream(bytes));
            DocumentType documentType = document.getDoctype();
            if (documentType != null) {
                throw new EpubImportException("INVALID_EPUB_XML", "DOCTYPE is not allowed in EPUB XML");
            }
            return document;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            String message = exception.getMessage() == null ? "invalid XML" : exception.getMessage();
            throw new EpubImportException("INVALID_EPUB_XML", "Invalid EPUB XML: " + message, exception);
        }
    }

    private void validateXmlRoot(org.w3c.dom.Document document, String expectedLocalName,
                                 String expectedNamespace, String description) {
        org.w3c.dom.Element root = document.getDocumentElement();
        if (root == null
                || !expectedLocalName.equals(localName(root))
                || !expectedNamespace.equals(root.getNamespaceURI())) {
            throw invalidStructure(description + " has an invalid root element");
        }
    }

    private org.w3c.dom.Element findFirstElement(org.w3c.dom.Document document, String localName) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            org.w3c.dom.Node node = nodes.item(index);
            if (node instanceof org.w3c.dom.Element element && localName(element).equals(localName)) {
                return element;
            }
        }
        return null;
    }

    private List<org.w3c.dom.Element> childElements(org.w3c.dom.Element parent, String localName) {
        List<org.w3c.dom.Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            org.w3c.dom.Node child = children.item(index);
            if (child instanceof org.w3c.dom.Element element && localName(element).equals(localName)) {
                elements.add(element);
            }
        }
        return elements;
    }

    private String localName(org.w3c.dom.Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = element.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon < 0 ? nodeName : nodeName.substring(colon + 1);
    }

    private String requiredAttribute(org.w3c.dom.Element element, String name) {
        if (element == null) {
            throw invalidStructure("EPUB XML is missing " + name);
        }
        String value = element.getAttribute(name).trim();
        if (value.isEmpty()) {
            throw invalidStructure("EPUB XML attribute is blank: " + name);
        }
        return value;
    }

    private String resolveEntryPath(String baseDirectory, String href) {
        if (href == null || href.isBlank() || href.indexOf('\\') >= 0) {
            throw invalidPath("EPUB entry path is invalid");
        }
        try {
            URI uri = URI.create(href);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null
                    || uri.getPath().startsWith("/")) {
                throw invalidPath("EPUB entry path is invalid: " + href);
            }
            String path = uri.getPath();
            return normalizeEntryPath(baseDirectory.isEmpty() ? path : baseDirectory + "/" + path);
        } catch (IllegalArgumentException exception) {
            if (exception instanceof EpubImportException) {
                throw exception;
            }
            throw invalidPath("EPUB entry path is invalid: " + href);
        }
    }

    private String normalizeEntryPath(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\u0000') >= 0
                || entryName.indexOf('\\') >= 0 || entryName.startsWith("/")
                || entryName.matches("[A-Za-z]:.*")) {
            throw invalidPath("EPUB entry path is outside EPUB root: " + entryName);
        }
        String[] parts = entryName.split("/", -1);
        List<String> normalizedParts = new ArrayList<>(parts.length);
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() && index == parts.length - 1) {
                continue;
            }
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw invalidPath("EPUB entry path is outside EPUB root: " + entryName);
            }
            normalizedParts.add(part);
        }
        if (normalizedParts.isEmpty()) {
            throw invalidPath("EPUB entry path is outside EPUB root: " + entryName);
        }
        return String.join("/", normalizedParts);
    }

    private String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private boolean isXhtml(String mediaType) {
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        return "application/xhtml+xml".equals(normalized) || "text/html".equals(normalized);
    }

    private boolean hasToken(String value, String token) {
        for (String part : value.split("\\s+")) {
            if (part.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private EpubImportException invalidStructure(String message) {
        return new EpubImportException("INVALID_EPUB_STRUCTURE", message);
    }

    private EpubImportException invalidPath(String message) {
        return new EpubImportException("INVALID_EPUB_PATH", message);
    }

    public record ArchiveLimits(int maxEntries, long maxEntryUncompressedBytes,
                                long maxTotalUncompressedBytes) {

        public ArchiveLimits {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
            if (maxEntryUncompressedBytes <= 0) {
                throw new IllegalArgumentException("maxEntryUncompressedBytes must be positive");
            }
            if (maxTotalUncompressedBytes <= 0) {
                throw new IllegalArgumentException("maxTotalUncompressedBytes must be positive");
            }
        }

        public static ArchiveLimits defaults() {
            return new ArchiveLimits(
                    DEFAULT_MAX_ZIP_ENTRIES,
                    DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                    DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES);
        }
    }

    private record ArchiveIndex(List<ZipEntry> orderedEntries, Map<String, ZipEntry> entries) {
    }

    private record ManifestItem(String href, String mediaType, boolean navigation) {
    }
}

record ChapterDraft(String title, String text, String textSha256) {

    ChapterDraft(String title, String text) {
        this(title, text, BookHashing.sha256(text));
    }

    ChapterDraft {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("chapter title must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("chapter text must not be null");
        }
        if (textSha256 == null || !textSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("textSha256 must be a SHA-256 hex digest");
        }
        textSha256 = textSha256.toLowerCase(Locale.ROOT);
    }
}
