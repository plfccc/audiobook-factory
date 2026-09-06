package com.audiobookfactory.control.book;

import com.audiobookfactory.control.job.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChapterAudioControllerTest {

    @Test
    void downloadReturnsAudioAsAttachment() throws Exception {
        Path audio = Files.createTempFile("chapter-", ".mp3");
        Files.write(audio, new byte[]{1, 2, 3});
        try {
            JobService jobService = mock(JobService.class);
            when(jobService.openChapterAudio(7L)).thenReturn(audio);

            ResponseEntity<Resource> response = new ChapterAudioController(jobService).download(7L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("attachment; filename=\"chapter-7.mp3\"");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getInputStream().readAllBytes())
                    .containsExactly(1, 2, 3);
        } finally {
            Files.deleteIfExists(audio);
        }
    }
}
