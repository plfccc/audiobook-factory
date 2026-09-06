package com.audiobookfactory.control.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 负责以数据库事务原子领取片段任务。候选行按章节和片段顺序选择，
 * 并用 PostgreSQL 行锁避免多个 Worker 同时领取同一任务。
 */
@Repository
public class JobClaimRepository {

    public static final int LEASE_SECONDS = 300;

    private static final String EXPIRED_LEASE_SQL = """
            UPDATE generation_job
            SET status = 'WAITING',
                lease_owner = NULL,
                lease_expires_at = NULL,
                heartbeat_at = NULL,
                error_code = NULL,
                error_message = NULL,
                next_retry_at = NULL,
                updated_at = ?
            WHERE status IN ('LEASED', 'GENERATING', 'UPLOADING')
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= ?
            """;

    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT gj.id
                FROM generation_job gj
                JOIN chapter c ON c.id = gj.chapter_id
                JOIN book_version bv ON bv.id = c.book_version_id
                JOIN book b ON b.id = bv.book_id
                WHERE b.status = 'RUNNING'
                  AND gj.scope_id IS NOT DISTINCT FROM b.active_scope_id
                  AND gj.scope_id IS NOT DISTINCT FROM COALESCE(?, b.active_scope_id)
                  AND c.status IN ('WAITING', 'RUNNING')
                  AND gj.status = 'WAITING'
                  AND (gj.next_retry_at IS NULL OR gj.next_retry_at <= ?)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM chapter previous
                      WHERE previous.book_version_id = c.book_version_id
                        AND previous.chapter_number < c.chapter_number
                        AND previous.status <> 'SUCCESS'
                  )
                ORDER BY c.chapter_number, gj.segment_index, gj.id
                FOR UPDATE OF gj SKIP LOCKED
                LIMIT 1
            )
            UPDATE generation_job gj
            SET status = 'LEASED',
                lease_owner = ?,
                lease_expires_at = ?::timestamptz + interval '5 minutes',
                heartbeat_at = ?,
                started_at = COALESCE(gj.started_at, ?),
                attempts = gj.attempts + 1,
                updated_at = ?
            FROM candidate
            JOIN chapter c ON c.id = gj.chapter_id
            JOIN book_version bv ON bv.id = c.book_version_id
            JOIN book b ON b.id = bv.book_id
            WHERE gj.id = candidate.id
            RETURNING gj.id AS job_id,
                      b.id AS book_id,
                      bv.id AS book_version_id,
                      c.id AS chapter_id,
                      c.chapter_number,
                      gj.segment_index,
                      gj.segment_text,
                      gj.preset_snapshot,
                      gj.lease_owner,
                      gj.lease_expires_at,
                      gj.attempts,
                      gj.run_id,
                      gj.batch_id,
                      gj.scope_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public JobClaimRepository(JdbcTemplate jdbcTemplate,
                              PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    /**
     * 为 Worker 领取一个当前可执行的片段；无任务时返回 {@code null}。
     * 传入的时间用于测试和恢复边界，生产调用方应传入当前 UTC 时间。
     *
     * @param workerId Worker 注册 ID
     * @param now 当前 UTC 时间
     * @return 领取结果，或无可领取任务时的 {@code null}
     */
    public JobClaim claimNext(String workerId, Instant now) {
        return claimNext(workerId, now, null);
    }

    public JobClaim claimNext(String workerId, Instant now, String scopeId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(now, "now must not be null");
        return transactionTemplate.execute(status ->
                claimNextInTransaction(workerId.trim(), now, scopeId));
    }

    private JobClaim claimNextInTransaction(String workerId, Instant now, String scopeId) {
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.update(EXPIRED_LEASE_SQL, timestamp, timestamp);
        List<JobClaim> claims = jdbcTemplate.query(
                CLAIM_SQL,
                (resultSet, rowNum) -> new JobClaim(
                        resultSet.getLong("job_id"),
                        resultSet.getLong("book_id"),
                        resultSet.getLong("book_version_id"),
                        resultSet.getLong("chapter_id"),
                        resultSet.getInt("chapter_number"),
                        resultSet.getInt("segment_index"),
                        resultSet.getString("segment_text"),
                        resultSet.getString("preset_snapshot"),
                        resultSet.getString("lease_owner"),
                        resultSet.getTimestamp("lease_expires_at").toInstant(),
                        resultSet.getInt("attempts"),
                        resultSet.getString("run_id"),
                        resultSet.getString("batch_id"),
                        resultSet.getString("scope_id")),
                scopeId,
                timestamp,
                workerId,
                timestamp,
                timestamp,
                timestamp,
                timestamp);
        if (claims.isEmpty()) {
            return null;
        }
        JobClaim claim = claims.get(0);
        jdbcTemplate.update(
                "UPDATE chapter SET status = CASE WHEN status = 'WAITING' THEN 'RUNNING' ELSE status END, "
                        + "updated_at = ? WHERE id = ?",
                timestamp, claim.chapterId());
        return claim;
    }
}
