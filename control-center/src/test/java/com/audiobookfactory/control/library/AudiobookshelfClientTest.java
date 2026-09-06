package com.audiobookfactory.control.library;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudiobookshelfClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void scanUsesConfiguredLibraryAndBearerKey() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));
        AudiobookshelfClient client = new AudiobookshelfClient(
                server.url("/").toString(), "library-1", "test-api-key");

        client.scan("library-1");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/libraries/library-1/scan");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
    }

    @Test
    void scanFailureDoesNotExposeApiKeyOrResponseBody() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("upstream response contains test-api-key"));
        AudiobookshelfClient client = new AudiobookshelfClient(
                server.url("/").toString(), "library-1", "test-api-key");

        assertThatThrownBy(() -> client.scan("library-1"))
                .hasMessageNotContaining("test-api-key")
                .hasMessageNotContaining("upstream response");
    }

    @Test
    void scanRejectsAnUnconfiguredLibrary() {
        AudiobookshelfClient client = new AudiobookshelfClient(
                server.url("/").toString(), "library-1", "test-api-key");

        assertThatThrownBy(() -> client.scan("library-2"))
                .isInstanceOf(AudiobookshelfClient.AudiobookshelfException.class)
                .hasMessageNotContaining("test-api-key");
    }
}
