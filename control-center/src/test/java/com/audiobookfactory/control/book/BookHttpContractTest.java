package com.audiobookfactory.control.book;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.ApiExceptionHandler;
import com.audiobookfactory.control.job.JobController;
import com.audiobookfactory.control.job.JobService;
import com.audiobookfactory.control.progress.ChapterProgress;
import com.audiobookfactory.control.progress.ProgressController;
import com.audiobookfactory.control.progress.ProgressQueryService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookHttpContractTest {

    @Mock
    BookService bookService;

    @Mock
    JobService jobService;

    @Mock
    ProgressQueryService progressQueryService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BookController(bookService),
                        new JobController(jobService),
                        new ProgressController(progressQueryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void importUsesFilePartAndReturnsStableCreatedContract() throws Exception {
        when(bookService.importBook(any())).thenReturn(new EpubImportService.ImportOutcome(
                new BookImportResult(7, 8, 2, "a".repeat(64)), true));

        mockMvc.perform(multipart("/api/v1/books")
                        .file("file", "epub-bytes".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").value(7))
                .andExpect(jsonPath("$.bookVersionId").value(8))
                .andExpect(jsonPath("$.chapterCount").value(2))
                .andExpect(jsonPath("$.sourceSha256").value("a".repeat(64)));
    }

    @Test
    void generationResponseDoesNotEchoPresetOrClonePrompt() throws Exception {
        when(jobService.createGeneration(eq(7L), any())).thenReturn(
                new JobService.JobBatch(7, List.of("11"), 1, 1, "GENERATION", "WAITING"));

        mockMvc.perform(post("/api/v1/books/7/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chapterStart\":1,\"chapterEnd\":1,"
                                + "\"preset\":{\"clone_prompt\":\"must-not-leak\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobIds[0]").value("11"))
                .andExpect(jsonPath("$.preset").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("must-not-leak"))));
    }

    @Test
    void progressKeepsChapterCountsAsThePrimaryContract() throws Exception {
        when(progressQueryService.get(7L)).thenReturn(new ChapterProgress(3, 10, 4));

        mockMvc.perform(get("/api/v1/books/7/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedChapters").value(3))
                .andExpect(jsonPath("$.totalChapters").value(10))
                .andExpect(jsonPath("$.currentChapter").value(4));
    }

    @Test
    void ttsModelContractExposesRuntimeModelSelectionFields() throws Exception {
        when(jobService.listTtsModels()).thenReturn(List.of(
                new JobService.TtsModelView(
                        "qwen3-tts",
                        JobService.DEFAULT_MODEL,
                        "1.0",
                        8L * 1024 * 1024 * 1024,
                        500,
                        new JobService.TtsCapabilities(
                                List.of("zh-CN", "en-US"), false, true, false, false),
                        "https://example.test/qwen-license")));

        mockMvc.perform(get("/api/v1/tts/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].engineId").value("qwen3-tts"))
                .andExpect(jsonPath("$[0].modelId").value(JobService.DEFAULT_MODEL))
                .andExpect(jsonPath("$[0].modelVersion").value("1.0"))
                .andExpect(jsonPath("$[0].minimumVramBytes").value(8L * 1024 * 1024 * 1024))
                .andExpect(jsonPath("$[0].capabilities.languages[0]").value("zh-CN"))
                .andExpect(jsonPath("$[0].capabilities.voiceClone").value(true));
    }

    @Test
    void ttsPresetContractExposesPersistedPresetFields() throws Exception {
        when(jobService.listTtsPresets()).thenReturn(List.of(
                new JobService.TtsPresetView(
                        9L,
                        "qwen3-tts",
                        JobService.DEFAULT_MODEL,
                        "1.0",
                        "自然朗读",
                        new java.math.BigDecimal("1.000"),
                        JsonNodeFactory.instance.objectNode().put("temperature", 0.7),
                        220)));

        mockMvc.perform(get("/api/v1/tts/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].engine").value("qwen3-tts"))
                .andExpect(jsonPath("$[0].model").value(JobService.DEFAULT_MODEL))
                .andExpect(jsonPath("$[0].modelVersion").value("1.0"))
                .andExpect(jsonPath("$[0].modelParameters.temperature").value(0.7))
                .andExpect(jsonPath("$[0].segmentLength").value(220));
    }

    @Test
    void businessErrorsUseCodeAndDoNotEchoRequestBody() throws Exception {
        doThrow(new ApiException("BOOK_NOT_FOUND", 404, "Book was not found"))
                .when(jobService).createGeneration(eq(999L), any());

        mockMvc.perform(post("/api/v1/books/999/generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":{\"clone_prompt\":\"secret-value\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-value"))));
    }
}
