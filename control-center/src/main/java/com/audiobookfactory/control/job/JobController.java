package com.audiobookfactory.control.job;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/books/{bookId}/preview")
    public ResponseEntity<JobService.JobBatch> createPreview(
            @PathVariable long bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(jobService.createPreview(bookId, request));
    }

    @PostMapping("/books/{bookId}/generation")
    public ResponseEntity<JobService.JobBatch> createGeneration(
            @PathVariable long bookId,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(jobService.createGeneration(bookId, request));
    }

    @PostMapping("/books/{bookId}/pause")
    public ResponseEntity<Void> pause(@PathVariable long bookId) {
        jobService.pauseBook(bookId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/books/{bookId}/resume")
    public ResponseEntity<Void> resume(@PathVariable long bookId) {
        jobService.resumeBook(bookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tts/models")
    public List<JobService.TtsModelView> listModels() {
        return jobService.listTtsModels();
    }

    @GetMapping("/tts/presets")
    public List<JobService.TtsPresetView> listPresets() {
        return jobService.listTtsPresets();
    }
}
