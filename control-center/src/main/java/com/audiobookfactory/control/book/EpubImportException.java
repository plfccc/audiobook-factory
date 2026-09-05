package com.audiobookfactory.control.book;

public final class EpubImportException extends IllegalArgumentException {

    private final String code;

    public EpubImportException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public EpubImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("error code must not be blank");
        }
        return code;
    }
}
