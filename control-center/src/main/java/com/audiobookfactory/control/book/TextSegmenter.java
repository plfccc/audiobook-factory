package com.audiobookfactory.control.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class TextSegmenter {

    public List<SegmentDraft> segment(String chapterText, SegmentationPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (chapterText == null || chapterText.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String normalizedText = chapterText.replace('\r', '\n');
        for (String paragraph : normalizedText.split("\\n+")) {
            String normalizedParagraph = normalizeParagraph(paragraph);
            if (!normalizedParagraph.isEmpty()) {
                appendParagraph(normalizedParagraph, policy, chunks);
            }
        }

        mergeShortTrailingChunk(chunks, policy);
        List<SegmentDraft> result = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            result.add(new SegmentDraft(index + 1, chunks.get(index)));
        }
        return List.copyOf(result);
    }

    private void appendParagraph(String paragraph, SegmentationPolicy policy, List<String> chunks) {
        StringBuilder current = new StringBuilder();
        for (String unit : splitAtPreferredBoundaries(paragraph)) {
            if (codePointLength(unit) > policy.maxChars()) {
                flush(current, chunks);
                chunks.addAll(splitLongUnit(unit, policy));
                continue;
            }

            if (current.isEmpty()) {
                current.append(unit);
                continue;
            }

            int combinedLength = codePointLength(current) + codePointLength(unit);
            if (combinedLength <= policy.targetChars()
                    || (codePointLength(current) < policy.minChars()
                    && combinedLength <= policy.maxChars())) {
                current.append(unit);
            } else {
                flush(current, chunks);
                current.append(unit);
            }
        }
        flush(current, chunks);
    }

    private List<String> splitAtPreferredBoundaries(String paragraph) {
        List<String> units = new ArrayList<>();
        int start = 0;
        int offset = 0;
        while (offset < paragraph.length()) {
            int codePoint = paragraph.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (isSentenceBoundary(codePoint)) {
                nextOffset = consumeClosingPunctuation(paragraph, nextOffset);
                addUnit(units, paragraph.substring(start, nextOffset));
                start = nextOffset;
            } else if (isSoftBoundary(codePoint)) {
                addUnit(units, paragraph.substring(start, nextOffset));
                start = nextOffset;
            }
            offset = nextOffset;
        }
        addUnit(units, paragraph.substring(start));
        return units;
    }

    private List<String> splitLongUnit(String unit, SegmentationPolicy policy) {
        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (codePointLength(unit.substring(offset)) > policy.maxChars()) {
            int remaining = codePointLength(unit.substring(offset));
            int take = Math.min(policy.targetChars(), remaining - policy.minChars());
            int remainder = remaining - take;
            if (remainder > 0 && remainder < policy.minChars()) {
                int balancedTake = take - (policy.minChars() - remainder);
                if (balancedTake >= policy.minChars()) {
                    take = balancedTake;
                }
            }
            if (take <= 0) {
                take = Math.min(policy.maxChars(), remaining);
            }
            int end = unit.offsetByCodePoints(offset, take);
            chunks.add(unit.substring(offset, end));
            offset = end;
        }
        if (offset < unit.length()) {
            chunks.add(unit.substring(offset));
        }
        return chunks;
    }

    private void mergeShortTrailingChunk(List<String> chunks, SegmentationPolicy policy) {
        if (chunks.size() < 2) {
            return;
        }
        int lastIndex = chunks.size() - 1;
        if (codePointLength(chunks.get(lastIndex)) < policy.minChars()
                && codePointLength(chunks.get(lastIndex - 1)) + codePointLength(chunks.get(lastIndex))
                <= policy.maxChars()) {
            chunks.set(lastIndex - 1, chunks.get(lastIndex - 1) + chunks.get(lastIndex));
            chunks.remove(lastIndex);
        }
    }

    private void flush(StringBuilder current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private void addUnit(List<String> units, String rawUnit) {
        String unit = rawUnit.stripTrailing();
        if (!unit.isEmpty()) {
            units.add(unit);
        }
    }

    private String normalizeParagraph(String paragraph) {
        return paragraph
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("(?<=[\\p{IsHan}。！？；：，、])\\s+(?=[\\p{IsHan}])", "")
                .trim();
    }

    private int codePointLength(CharSequence text) {
        return text.toString().codePointCount(0, text.length());
    }

    private int consumeClosingPunctuation(String text, int offset) {
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (!"\"'”’）)]】》〉」』」".contains(new String(Character.toChars(codePoint)))) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return offset;
    }

    private boolean isSentenceBoundary(int codePoint) {
        return "。！？!?；;.……".indexOf(codePoint) >= 0;
    }

    private boolean isSoftBoundary(int codePoint) {
        return "，,、".indexOf(codePoint) >= 0;
    }
}

record SegmentDraft(int index, String text, String textSha256) {

    SegmentDraft(int index, String text) {
        this(index, text, BookHashing.sha256(text));
    }

    SegmentDraft(String text) {
        this(1, text);
    }

    SegmentDraft {
        if (index <= 0) {
            throw new IllegalArgumentException("segment index must be positive");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("segment text must not be blank");
        }
        if (textSha256 == null || !textSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("textSha256 must be a SHA-256 hex digest");
        }
        textSha256 = textSha256.toLowerCase(Locale.ROOT);
    }

    public int segmentIndex() {
        return index;
    }
}
