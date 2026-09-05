package com.audiobookfactory.control.book;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public record BookImportResult(
        long bookId,
        long bookVersionId,
        int chapterCount,
        String sourceSha256) {

    public BookImportResult {
        if (bookId <= 0) {
            throw new IllegalArgumentException("bookId must be positive");
        }
        if (bookVersionId <= 0) {
            throw new IllegalArgumentException("bookVersionId must be positive");
        }
        if (chapterCount < 0) {
            throw new IllegalArgumentException("chapterCount must not be negative");
        }
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be a SHA-256 hex digest");
        }
        sourceSha256 = sourceSha256.toLowerCase(Locale.ROOT);
    }
}

final class BookHashing {

    private BookHashing() {
    }

    static String sha256(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

final class EpubImportException extends IllegalArgumentException {

    private final String code;

    EpubImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    EpubImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
