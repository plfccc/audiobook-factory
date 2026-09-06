package com.audiobookfactory.control.library;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

@Service
public class AudiobookshelfClient {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final String ENV_BASE_URL = "AUDIOBOOKSHELF_BASE_URL";
    private static final String ENV_LIBRARY_ID = "AUDIOBOOKSHELF_LIBRARY_ID";
    private static final String ENV_API_KEY = "AUDIOBOOKSHELF_API_KEY";

    private final URI baseUri;
    private final String configuredLibraryId;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    @Autowired
    public AudiobookshelfClient() {
        this(System.getenv(ENV_BASE_URL), System.getenv(ENV_LIBRARY_ID), System.getenv(ENV_API_KEY));
    }

    public AudiobookshelfClient(String baseUrl, String configuredLibraryId, String apiKey) {
        this(baseUrl, configuredLibraryId, apiKey,
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT);
    }

    public AudiobookshelfClient(String baseUrl, String apiKey) {
        this(baseUrl, null, apiKey);
    }

    public AudiobookshelfClient(String baseUrl, String configuredLibraryId, String apiKey,
                                HttpClient httpClient, Duration requestTimeout) {
        this.baseUri = parseBaseUri(baseUrl);
        this.configuredLibraryId = blankToNull(configuredLibraryId);
        this.apiKey = blankToNull(apiKey);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout = requireTimeout(requestTimeout);
    }

    public void scan() {
        scan(configuredLibraryId);
    }

    public void scan(String libraryId) {
        String effectiveLibraryId = blankToNull(libraryId);
        if (effectiveLibraryId == null) {
            effectiveLibraryId = configuredLibraryId;
        }
        validateConfiguration(effectiveLibraryId);
        if (configuredLibraryId != null && !configuredLibraryId.equals(effectiveLibraryId)) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_CONFIG_INVALID",
                    "Audiobookshelf library is not configured for this library");
        }

        URI scanUri = scanUri(effectiveLibraryId);
        HttpRequest request = HttpRequest.newBuilder(scanUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AudiobookshelfException("AUDIOBOOKSHELF_SCAN_FAILED",
                        "Audiobookshelf scan returned HTTP " + response.statusCode());
            }
        } catch (AudiobookshelfException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_TIMEOUT",
                    "Audiobookshelf scan timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AudiobookshelfException("AUDIOBOOKSHELF_INTERRUPTED",
                    "Audiobookshelf scan was interrupted", exception);
        } catch (Exception exception) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_UNAVAILABLE",
                    "Audiobookshelf scan could not be completed", exception);
        }
    }

    public String configuredLibraryId() {
        return configuredLibraryId;
    }

    private void validateConfiguration(String libraryId) {
        if (baseUri == null || !"http".equalsIgnoreCase(baseUri.getScheme())
                && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_CONFIG_INVALID",
                    "Audiobookshelf base URL is invalid");
        }
        if (apiKey == null || apiKey.length() > 512 || containsControl(apiKey)) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_CONFIG_INVALID",
                    "Audiobookshelf API key is not configured");
        }
        validateLibraryId(libraryId);
    }

    private URI scanUri(String libraryId) {
        String basePath = baseUri.getPath() == null ? "" : baseUri.getPath();
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String path = basePath + "/api/libraries/" + libraryId + "/scan";
        try {
            return new URI(baseUri.getScheme(), baseUri.getRawAuthority(), path, null, null);
        } catch (URISyntaxException exception) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_CONFIG_INVALID",
                    "Audiobookshelf scan URL is invalid", exception);
        }
    }

    private void validateLibraryId(String libraryId) {
        if (libraryId == null || libraryId.length() > 128
                || !libraryId.matches("[A-Za-z0-9][A-Za-z0-9._~-]*")) {
            throw new AudiobookshelfException("AUDIOBOOKSHELF_CONFIG_INVALID",
                    "Audiobookshelf library is invalid");
        }
    }

    private URI parseBaseUri(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || containsControl(normalized)) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawAuthority() == null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Duration requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "requestTimeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return timeout;
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class AudiobookshelfException extends RuntimeException {
        private final String code;

        public AudiobookshelfException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public AudiobookshelfException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public String code() {
            return code;
        }
    }
}
