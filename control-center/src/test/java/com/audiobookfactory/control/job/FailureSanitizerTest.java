package com.audiobookfactory.control.job;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FailureSanitizerTest {

    @ParameterizedTest(name = "unsafe failure detail is replaced: {0}")
    @ValueSource(strings = {
            "Bearer abc123",
            "You are a helpful assistant. Read this sentence aloud.",
            "at com.example.worker.Worker.run(Worker.java:42)",
            "https://internal.example/api/v1/jobs/42",
            "C:\\service\\worker\\logs\\worker.log"
    })
    void replacesCredentialsPromptsStacksPathsAndUrlsWithoutRelyingOnKeywords(String detail) {
        assertThat(FailureSanitizer.sanitizeSummary(detail))
                .isEqualTo(FailureSanitizer.GENERIC_SUMMARY);
    }

    @org.junit.jupiter.api.Test
    void preservesAShortPlainFailureSummary() {
        assertThat(FailureSanitizer.sanitizeSummary("audio invalid"))
                .isEqualTo("audio invalid");
    }
}
