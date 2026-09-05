package com.audiobookfactory.control.worker;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.ApiExceptionHandler;
import com.audiobookfactory.control.job.JobClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerHttpContractTest {

    @Mock
    WorkerService workerService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkerController(workerService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void registerReturnsTask5WorkerFieldsWithoutEnrollmentSecret() throws Exception {
        when(workerService.register(eq("enroll-secret"), any())).thenReturn(
                new WorkerService.RegistrationResponse("worker-1", "short-worker-token", 300,
                        "ACTIVE"));

        mockMvc.perform(post("/api/v1/workers/register")
                        .header("Authorization", "Bearer enroll-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerName\":\"colab-1\",\"runtime\":{},"
                                + "\"capabilities\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("worker-1"))
                .andExpect(jsonPath("$.workerToken").value("short-worker-token"))
                .andExpect(jsonPath("$.leaseSeconds").value(300))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("enroll-secret"))));
    }

    @Test
    void claimReturns204WhenThereIsNoWork() throws Exception {
        when(workerService.claim("worker-token")).thenReturn(null);

        mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void claimUsesTask5FieldNamesAndDoesNotExposeClonePrompt() throws Exception {
        JobClaim claim = new JobClaim(11, 7, 8, 9, 1, 1, "测试。",
                "{\"provider\":\"qwen3-tts\",\"clone_prompt\":\"secret\"}",
                "worker-1", Instant.parse("2026-09-05T00:05:00Z"), 1);
        when(workerService.claim("worker-token")).thenReturn(claim);

        mockMvc.perform(post("/api/v1/workers/claim")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("11"))
                .andExpect(jsonPath("$.chapterIndex").value(1))
                .andExpect(jsonPath("$.segmentIndex").value(1))
                .andExpect(jsonPath("$.text").value("测试。"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("clone_prompt"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void lostLeaseReturnsConflictAndLeavesErrorCodeStable() throws Exception {
        doThrow(new ApiException("LEASE_LOST", 409, "Worker lease is no longer valid"))
                .when(workerService).heartbeat(eq("worker-token"), eq(11L), any());

        mockMvc.perform(post("/api/v1/workers/jobs/11/heartbeat")
                        .header("Authorization", "Bearer worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"generating\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_LOST"));
    }
}
