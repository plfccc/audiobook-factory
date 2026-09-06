package com.audiobookfactory.control.book;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.job.JobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/chapters")
public class ChapterAudioController {

    private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");

    private final JobService jobService;

    public ChapterAudioController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{chapterId}/audio")
    public ResponseEntity<Resource> audio(@PathVariable long chapterId) {
        return audioResponse(chapterId, "inline");
    }

    @GetMapping("/{chapterId}/audio/download")
    public ResponseEntity<Resource> download(@PathVariable long chapterId) {
        return audioResponse(chapterId, "attachment; filename=\"chapter-" + chapterId + ".mp3\"");
    }

    private ResponseEntity<Resource> audioResponse(long chapterId, String contentDisposition) {
        Path path = jobService.openChapterAudio(chapterId);
        FileSystemResource resource = new FileSystemResource(path);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(AUDIO_MPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        try {
            return response.contentLength(resource.contentLength()).body(resource);
        } catch (IOException exception) {
            throw new ApiException("CHAPTER_AUDIO_NOT_FOUND", 404, "Chapter audio is not ready");
        }
    }
}
