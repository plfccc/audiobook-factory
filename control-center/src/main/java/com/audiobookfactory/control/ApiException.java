package com.audiobookfactory.control;

/**
 * 统一承载可由 HTTP 层安全返回的业务错误，不保存请求中的凭据或敏感内容。
 */
public final class ApiException extends RuntimeException {

    private final String code;
    private final int status;

    public ApiException(String code, int status, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("error code must not be blank");
        }
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("HTTP status must be an error status");
        }
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
