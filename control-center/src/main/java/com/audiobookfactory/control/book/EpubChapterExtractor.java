package com.audiobookfactory.control.book;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.DocumentType;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class EpubChapterExtractor {

    private static final Set<String> NOISE_TAGS = Set.of(
            "nav", "script", "style", "header", "footer", "aside", "form");

    public List<ChapterDraft> extract(Path epubPath) throws IOException {
        if (epubPath == null) {
            throw new IllegalArgumentException("epubPath must not be null");
        }
        if (!java.nio.file.Files.isRegularFile(epubPath)) {
            throw new IllegalArgumentException("EPUB file does not exist: " + epubPath);
        }

        try (ZipFile epub = new ZipFile(epubPath.toFile())) {
            Map<String, ZipEntry> entries = indexEntries(epub);
            String containerPath = normalizeEntryPath("META-INF/container.xml");
            org.w3c.dom.Document container = parseXml(readEntry(epub, entries, containerPath));
            String opfPath = requiredAttribute(findFirstElement(container, "rootfile"), "full-path");
            opfPath = resolveEntryPath("", opfPath);
            org.w3c.dom.Document opf = parseXml(readEntry(epub, entries, opfPath));

            Map<String, ManifestItem> manifest = readManifest(opf);
            org.w3c.dom.Element spine = findFirstElement(opf, "spine");
            if (spine == null) {
                throw new IllegalArgumentException("EPUB OPF does not contain a spine");
            }
            String opfDirectory = parentDirectory(opfPath);
            List<ChapterDraft> chapters = new ArrayList<>();
            int chapterNumber = 1;
            for (org.w3c.dom.Element itemRef : childElements(spine, "itemref")) {
                if ("no".equalsIgnoreCase(itemRef.getAttribute("linear"))) {
                    continue;
                }
                String idref = itemRef.getAttribute("idref").trim();
                ManifestItem item = manifest.get(idref);
                if (item == null) {
                    throw new IllegalArgumentException("EPUB spine references missing manifest item: " + idref);
                }
                if (item.navigation() || !isXhtml(item.mediaType())) {
                    continue;
                }
                String chapterPath = resolveEntryPath(opfDirectory, item.href());
                ChapterDraft chapter = extractChapter(readEntry(epub, entries, chapterPath), chapterNumber);
                if (!chapter.text().isBlank()) {
                    chapters.add(chapter);
                    chapterNumber++;
                }
            }
            return List.copyOf(chapters);
        }
    }

    private Map<String, ZipEntry> indexEntries(ZipFile epub) {
        Map<String, ZipEntry> entries = new HashMap<>();
        Enumeration<? extends ZipEntry> enumeration = epub.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String normalized = normalizeEntryPath(entry.getName());
            if (!entries.containsKey(normalized)) {
                entries.put(normalized, entry);
            } else {
                throw new IllegalArgumentException("EPUB contains duplicate entry: " + normalized);
            }
        }
        return entries;
    }

    private byte[] readEntry(ZipFile epub, Map<String, ZipEntry> entries, String path) throws IOException {
        ZipEntry entry = entries.get(path);
        if (entry == null || entry.isDirectory()) {
            throw new IllegalArgumentException("EPUB entry not found: " + path);
        }
        try (InputStream input = epub.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private Map<String, ManifestItem> readManifest(org.w3c.dom.Document opf) {
        org.w3c.dom.Element manifest = findFirstElement(opf, "manifest");
        if (manifest == null) {
            throw new IllegalArgumentException("EPUB OPF does not contain a manifest");
        }
        Map<String, ManifestItem> items = new HashMap<>();
        for (org.w3c.dom.Element element : childElements(manifest, "item")) {
            String id = element.getAttribute("id").trim();
            String href = requiredAttribute(element, "href");
            String mediaType = requiredAttribute(element, "media-type");
            String properties = element.getAttribute("properties");
            if (id.isEmpty() || items.put(id, new ManifestItem(href, mediaType, hasToken(properties, "nav"))) != null) {
                throw new IllegalArgumentException("EPUB manifest contains an invalid or duplicate id");
            }
        }
        return items;
    }

    private ChapterDraft extractChapter(byte[] xhtml, int chapterNumber) throws IOException {
        Document document = Jsoup.parse(
                new ByteArrayInputStream(xhtml),
                null,
                "",
                Parser.xmlParser());
        Element body = document.selectFirst("body");
        if (body == null) {
            body = document;
        }

        removeNoise(body);
        Element heading = firstHeading(body);
        String title = heading == null ? normalizeText(document.title()) : normalizeText(heading.text());
        if (heading != null) {
            heading.remove();
        }
        removeRepeatedTitleElements(body, title);

        String text = normalizeText(body.text());
        text = removeLeadingDuplicateTitle(text, title);
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

    private void removeRepeatedTitleElements(Element body, String title) {
        if (title.isEmpty()) {
            return;
        }
        for (Element element : new ArrayList<>(body.getAllElements())) {
            if (element != body && element.children().isEmpty() && normalizeText(element.text()).equals(title)) {
                element.remove();
            }
        }
    }

    private String removeLeadingDuplicateTitle(String text, String title) {
        if (title.isEmpty() || !text.startsWith(title)) {
            return text;
        }
        if (text.length() == title.length()) {
            return "";
        }
        char next = text.charAt(title.length());
        if (Character.isWhitespace(next) || "，。！？!?；：:、".indexOf(next) >= 0) {
            return text.substring(title.length()).trim();
        }
        return text;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("(?<=[\\p{IsHan}。！？；：，、])\\s+(?=[\\p{IsHan}])", "")
                .trim();
    }

    private org.w3c.dom.Document parseXml(byte[] bytes) throws IOException {
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
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
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
                throw new IllegalArgumentException("DOCTYPE is not allowed in EPUB XML");
            }
            return document;
        } catch (ParserConfigurationException | SAXException exception) {
            String message = exception.getMessage() == null ? "invalid XML" : exception.getMessage();
            throw new IllegalArgumentException("Invalid EPUB XML: " + message, exception);
        }
    }

    private org.w3c.dom.Element findFirstElement(org.w3c.dom.Document document, String localName) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
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
            Node child = children.item(index);
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
            throw new IllegalArgumentException("EPUB XML is missing " + name);
        }
        String value = element.getAttribute(name).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("EPUB XML attribute is blank: " + name);
        }
        return value;
    }

    private String resolveEntryPath(String baseDirectory, String href) {
        if (href == null || href.isBlank() || href.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("EPUB entry path is invalid");
        }
        try {
            URI uri = URI.create(href);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null
                    || uri.getPath().startsWith("/")) {
                throw new IllegalArgumentException("EPUB entry path is invalid");
            }
            String path = uri.getPath();
            return normalizeEntryPath(baseDirectory.isEmpty() ? path : baseDirectory + "/" + path);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("outside EPUB root")) {
                throw exception;
            }
            throw new IllegalArgumentException("EPUB entry path is invalid: " + href, exception);
        }
    }

    private String normalizeEntryPath(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\u0000') >= 0
                || entryName.indexOf('\\') >= 0 || entryName.startsWith("/")
                || entryName.matches("[A-Za-z]:.*")) {
            throw new IllegalArgumentException("EPUB entry path is outside EPUB root: " + entryName);
        }
        Path normalized = Path.of(entryName).normalize();
        if (normalized.isAbsolute() || normalized.getNameCount() == 0
                || "..".equals(normalized.getName(0).toString())) {
            throw new IllegalArgumentException("EPUB entry path is outside EPUB root: " + entryName);
        }
        return normalized.toString().replace('\\', '/');
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
