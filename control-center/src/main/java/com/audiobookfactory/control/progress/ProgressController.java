package com.audiobookfactory.control.progress;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
public class ProgressController {

    private final ProgressQueryService progressQueryService;

    public ProgressController(ProgressQueryService progressQueryService) {
        this.progressQueryService = progressQueryService;
    }

    @GetMapping("/{bookId}/progress")
    public ChapterProgress getProgress(@PathVariable long bookId) {
        return progressQueryService.get(bookId);
    }
}
