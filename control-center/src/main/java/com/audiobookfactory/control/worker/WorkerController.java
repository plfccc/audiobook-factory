package com.audiobookfactory.control.worker;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.job.JobClaim;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class WorkerController {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "cloneprompt", "workertoken", "enrollmenttoken", "enrolltoken", "accesstoken");

    private final WorkerService workerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public WorkerController(WorkerService workerService, ObjectMapper objectMapper) {
        this.workerService = workerService;
        this.objectMapper = objectMapper;
    }

    public WorkerController(WorkerService workerService) {
        this(workerService, new ObjectMapper());
    }

    @PostMapping("/workers/register")
    public WorkerService.RegistrationResponse register(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> request) {
        return workerService.register(bearerToken(authorization), request);
    }

    @PostMapping("/workers/claim")
    public ResponseEntity<Map<String, Object>> claim(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        JobClaim claim = workerService.claim(bearerToken(authorization));
        if (claim == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(claimBody(claim));
    }

    @PostMapping("/workers/jobs/{jobId}/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String jobId,
            @RequestBody(required = false) Map<String, Object> progress) {
        workerService.heartbeat(bearerToken(authorization), parseId(jobId, "jobId"), progress);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workers/jobs/{jobId}/result")
    public ResponseEntity<Void> result(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String jobId,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "metadata", required = false) String metadata) {
        workerService.result(bearerToken(authorization), parseId(jobId, "jobId"), audio, metadata);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workers/jobs/{jobId}/failure")
    public ResponseEntity<Void> failure(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String jobId,
            @RequestBody(required = false) Map<String, Object> request) {
        workerService.failure(bearerToken(authorization), parseId(jobId, "jobId"), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assets/{assetId}/download")
    public ResponseEntity<Resource> download(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String assetId) {
        WorkerService.AssetDownload asset = workerService.openAsset(
                bearerToken(authorization), parseId(assetId, "assetId"));
        FileSystemResource resource = new FileSystemResource(asset.path());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"asset-" + assetId + "." + safeFormat(asset.format()) + "\"");
        if (asset.sizeBytes() != null && asset.sizeBytes() >= 0) {
            response.contentLength(asset.sizeBytes());
        }
        return response.body(resource);
    }

    private Map<String, Object> claimBody(JobClaim claim) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", Long.toString(claim.jobId()));
        body.put("bookId", Long.toString(claim.bookId()));
        body.put("bookVersionId", Long.toString(claim.bookVersionId()));
        body.put("chapterId", Long.toString(claim.chapterId()));
        body.put("chapterIndex", claim.chapterIndex());
        body.put("segmentIndex", claim.segmentIndex());
        body.put("text", claim.text());
        body.put("preset", safePreset(claim.presetSnapshot()));
        body.put("leaseOwner", claim.leaseOwner());
        body.put("leaseUntil", claim.leaseUntil());
        body.put("leaseSeconds", WorkerService.LEASE_SECONDS);
        return body;
    }

    private JsonNode safePreset(String snapshot) {
        try {
            JsonNode parsed = snapshot == null || snapshot.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(snapshot);
            return sanitize(parsed == null ? objectMapper.createObjectNode() : parsed);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode clean = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!SENSITIVE_KEYS.contains(normalizeKey(field.getKey()))) {
                    clean.set(field.getKey(), sanitize(field.getValue()));
                }
            }
            return clean;
        }
        if (node.isArray()) {
            ArrayNode clean = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                clean.add(sanitize(child));
            }
            return clean;
        }
        return node.deepCopy();
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new ApiException("WORKER_UNAUTHORIZED", 401, "Worker authorization is required");
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank()) {
            throw new ApiException("WORKER_UNAUTHORIZED", 401, "Worker authorization is required");
        }
        return token;
    }

    private long parseId(String value, String name) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ApiException("INVALID_ID", 400, name + " must be positive");
        }
    }

    private String safeFormat(String format) {
        if (format == null || !format.matches("[a-zA-Z0-9]{1,16}")) {
            return "bin";
        }
        return format.toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
