package com.audiobookfactory.control.worker;

import com.audiobookfactory.control.ApiException;
import com.audiobookfactory.control.config.AppProperties;
import com.audiobookfactory.control.job.JobClaim;
import com.audiobookfactory.control.job.JobClaimRepository;
import com.audiobookfactory.control.job.JobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkerService {

    public static final int LEASE_SECONDS = JobClaimRepository.LEASE_SECONDS;
    private static final Duration WORKER_TOKEN_TTL = Duration.ofHours(12);
    private static final Duration WORKER_OFFLINE_AFTER = Duration.ofSeconds(90);
    private static final long ENROLLMENT_LOCK_KEY = 4_381_927_611L;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "cloneprompt", "workertoken", "enrollmenttoken", "enrolltoken", "accesstoken");
    private static final Set<String> ACTIVE_JOB_STATUSES = Set.of(
            "LEASED", "GENERATING", "UPLOADING");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final JobClaimRepository claimRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final Path storageRoot;

    @Autowired
    public WorkerService(JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager,
                         JobClaimRepository claimRepository,
                         JobService jobService,
                         ObjectMapper objectMapper,
                         AppProperties appProperties) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.claimRepository = Objects.requireNonNull(claimRepository, "claimRepository must not be null");
        this.jobService = Objects.requireNonNull(jobService, "jobService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties must not be null");
        this.storageRoot = Objects.requireNonNull(appProperties.storageRoot(), "storageRoot must not be null")
                .toAbsolutePath().normalize();
    }

    public RegistrationResponse register(String enrollmentToken, Map<String, Object> request) {
        requireToken(enrollmentToken, "WORKER_UNAUTHORIZED");
        String configuredToken = appProperties.workerEnrollToken();
        if (!constantTimeEquals(configuredToken, enrollmentToken)) {
            throw unauthorized();
        }

        Map<String, Object> body = request == null ? Map.of() : request;
        String workerName = text(body, "workerName", "worker_name");
        if (workerName == null || workerName.length() > 128) {
            throw new ApiException("INVALID_WORKER", 400, "Worker name is invalid");
        }
        String capabilities = capabilitiesJson(body);
        try {
            RegistrationResponse result = transactionTemplate.execute(status -> {
                // PostgreSQL advisory transaction lock makes the one-time enrollment atomic across instances.
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ENROLLMENT_LOCK_KEY + ")");
                Boolean used = jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM worker_registration)", Boolean.class);
                if (Boolean.TRUE.equals(used)) {
                    throw unauthorized();
                }

                String workerId = "worker-" + UUID.randomUUID();
                String workerToken = generateToken();
                jdbcTemplate.update("""
                        INSERT INTO worker_registration
                            (worker_id, name, capabilities, token_hash, status, last_heartbeat_at)
                        VALUES (?, ?, CAST(? AS jsonb), ?, 'ACTIVE', CURRENT_TIMESTAMP)
                        """, workerId, workerName, capabilities, sha256(workerToken));
                return new RegistrationResponse(workerId, workerToken, LEASE_SECONDS, "ACTIVE");
            });
            if (result == null) {
                throw new IllegalStateException("worker registration transaction returned no result");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            throw unauthorized();
        }
    }

    public JobClaim claim(String workerToken) {
        return claim(workerToken, null);
    }

    public JobClaim claim(String workerToken, String scopeId) {
        WorkerIdentity worker = authenticate(workerToken);
        return claimRepository.claimNext(worker.workerId(), Instant.now(), scopeId);
    }

    public StatusSnapshot status() {
        List<WorkerStatusRow> workers = jdbcTemplate.query("""
                SELECT worker_id, name, status, capabilities, last_heartbeat_at, updated_at
                FROM worker_registration
                ORDER BY updated_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> new WorkerStatusRow(
                resultSet.getString("worker_id"),
                resultSet.getString("name"),
                resultSet.getString("status"),
                resultSet.getString("capabilities"),
                resultSet.getTimestamp("last_heartbeat_at"),
                resultSet.getTimestamp("updated_at")));
        if (workers.isEmpty()) {
            return StatusSnapshot.notConnected();
        }

        WorkerStatusRow worker = workers.get(0);
        Instant lastHeartbeatAt = toInstant(worker.lastHeartbeatAt());
        Instant updatedAt = toInstant(worker.updatedAt());
        String status = "ACTIVE".equalsIgnoreCase(worker.status())
                && lastHeartbeatAt != null
                && lastHeartbeatAt.plus(WORKER_OFFLINE_AFTER).isAfter(Instant.now())
                ? "ONLINE" : "ACTIVE".equalsIgnoreCase(worker.status()) ? "OFFLINE" : "EXPIRED";
        return new StatusSnapshot(
                status,
                worker.workerId(),
                worker.name(),
                lastHeartbeatAt,
                updatedAt,
                parseCapabilities(worker.capabilities()));
    }

    public void heartbeat(String workerToken, long jobId, Map<String, Object> progress) {
        WorkerIdentity worker = authenticate(workerToken);
        jobService.heartbeat(jobId, worker.workerId());
    }

    public void result(String workerToken, long jobId, MultipartFile audio, String metadataJson) {
        WorkerIdentity worker = authenticate(workerToken);
        jobService.recordResult(jobId, worker.workerId(), audio, metadataJson);
    }

    public void failure(String workerToken, long jobId, Map<String, Object> request) {
        WorkerIdentity worker = authenticate(workerToken);
        Map<String, Object> body = request == null ? Map.of() : request;
        jobService.recordFailure(jobId, worker.workerId(),
                text(body, "code", "errorCode", "error_code"),
                text(body, "message", "error", "detail"));
    }

    public AssetDownload openAsset(String workerToken, long assetId) {
        WorkerIdentity worker = authenticate(workerToken);
        if (assetId <= 0) {
            throw new ApiException("INVALID_ID", 400, "assetId must be positive");
        }
        List<AssetRow> assets = jdbcTemplate.query("""
                SELECT aa.file_path, aa.format, aa.size_bytes
                FROM audio_asset aa
                JOIN generation_job gj ON gj.id = aa.job_id
                WHERE aa.id = ?
                  AND gj.lease_owner = ?
                  AND gj.status IN ('LEASED', 'GENERATING', 'UPLOADING')
                  AND gj.lease_expires_at > CURRENT_TIMESTAMP
                """, (resultSet, rowNum) -> new AssetRow(
                resultSet.getString("file_path"), resultSet.getString("format"),
                resultSet.getObject("size_bytes", Long.class)), assetId, worker.workerId());
        if (assets.isEmpty()) {
            throw new ApiException("ASSET_NOT_FOUND", 404, "Asset was not found");
        }
        AssetRow asset = assets.get(0);
        Path path = safeStoragePath(asset.filePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException("ASSET_NOT_FOUND", 404, "Asset was not found");
        }
        return new AssetDownload(path, asset.format(), asset.sizeBytes());
    }

    public WorkerIdentity authenticate(String workerToken) {
        requireToken(workerToken, "WORKER_UNAUTHORIZED");
        String tokenHash = sha256(workerToken);
        List<WorkerRow> workers = jdbcTemplate.query("""
                SELECT worker_id, name, status, created_at
                FROM worker_registration
                WHERE token_hash = ? AND status = 'ACTIVE'
                """, (resultSet, rowNum) -> new WorkerRow(
                resultSet.getString("worker_id"), resultSet.getString("name"),
                resultSet.getString("status"), resultSet.getTimestamp("created_at")), tokenHash);
        if (workers.isEmpty()) {
            throw unauthorized();
        }
        WorkerRow worker = workers.get(0);
        Instant createdAt = worker.createdAt() == null ? null : worker.createdAt().toInstant();
        if (createdAt == null || !createdAt.plus(WORKER_TOKEN_TTL).isAfter(Instant.now())) {
            jdbcTemplate.update("UPDATE worker_registration SET status='EXPIRED', updated_at=CURRENT_TIMESTAMP "
                    + "WHERE worker_id=? AND status='ACTIVE'", worker.workerId());
            throw unauthorized();
        }
        jdbcTemplate.update("UPDATE worker_registration SET last_heartbeat_at=CURRENT_TIMESTAMP, "
                + "updated_at=CURRENT_TIMESTAMP WHERE worker_id=? AND status='ACTIVE'", worker.workerId());
        return new WorkerIdentity(worker.workerId(), worker.name());
    }

    private JsonNode parseCapabilities(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String capabilitiesJson(Map<String, Object> body) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("runtime", sanitize(valueAsJson(body.get("runtime"))));
        root.set("capabilities", sanitize(valueAsJson(body.get("capabilities"))));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new ApiException("INVALID_WORKER", 400, "Worker capabilities are invalid");
        }
    }

    private JsonNode valueAsJson(Object value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_WORKER", 400, "Worker capabilities are invalid");
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

    private Path safeStoragePath(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException("ASSET_NOT_FOUND", 404, "Asset was not found");
        }
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (RuntimeException exception) {
            throw new ApiException("ASSET_NOT_FOUND", 404, "Asset was not found");
        }
        Path normalized = candidate.isAbsolute()
                ? candidate.normalize() : storageRoot.resolve(candidate).normalize();
        if (!normalized.startsWith(storageRoot)) {
            throw new ApiException("ASSET_NOT_FOUND", 404, "Asset was not found");
        }
        return normalized;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String requireToken(String token, String code) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new ApiException(code, 401, "Worker authorization is required");
        }
        return token;
    }

    private ApiException unauthorized() {
        return new ApiException("WORKER_UNAUTHORIZED", 401, "Worker authorization is required");
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormatHolder.format(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String text(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record WorkerRow(String workerId, String name, String status, Timestamp createdAt) {
    }

    private record WorkerStatusRow(String workerId, String name, String status,
                                   String capabilities, Timestamp lastHeartbeatAt, Timestamp updatedAt) {
    }

    private record AssetRow(String filePath, String format, Long sizeBytes) {
    }

    public record RegistrationResponse(String workerId, String workerToken,
                                       int leaseSeconds, String status) {
    }

    public record WorkerIdentity(String workerId, String name) {
    }

    public record StatusSnapshot(String status, String workerId, String name,
                                 Instant lastHeartbeatAt, Instant updatedAt, JsonNode capabilities) {

        public static StatusSnapshot notConnected() {
            return new StatusSnapshot("NOT_CONNECTED", null, null, null, null,
                    JsonNodeFactory.instance.objectNode());
        }
    }

    public record AssetDownload(Path path, String format, Long sizeBytes) {
    }

    private static final class HexFormatHolder {
        private static String format(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }
    }
}
