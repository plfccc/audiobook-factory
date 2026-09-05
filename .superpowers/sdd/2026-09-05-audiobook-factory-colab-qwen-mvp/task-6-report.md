# Task 6 服务端任务安全修复报告

状态：DONE_WITH_CONCERNS

## 最终修复提交

- Task 6 Worker 安全回归修复及测试提交：`89b4845122c1a4e605a29ef4dc087c3d400cc4de`
- 基线：`c5535b2931d8ff290646ec825a675291e3aba363`
- 本次补齐了 Worker 生成结果 SHA-256 校验、Provider `AUTH_REQUIRED` 等待态、上传前 SHA 校验，以及 failure 摘要的脱敏和 240 字符上限。
- 服务端已提交的 `JobService` / `FailureSanitizer` 改动保持不变；客户端约束与服务端的 SHA、认证错误映射和 failure 脱敏边界一致。

## 验证结果

- Java 服务端复审结果（承接基线提交）：`65 tests`，`Failures 0`，`Errors 0`，`Skipped 1`。
- Python 目标测试：`rtk pytest -q worker/tests/test_colab_worker.py worker/tests/test_server_client.py` → `38 passed`。
- 差异检查：`rtk git diff --check` 通过，无空白错误。

## Docker / Testcontainers

- Docker/Testcontainers：`Deferred`。本轮不等待或启动外部环境。

## 真实 JDBC 联调顾虑

- Task 6 的 JobService/BookService 安全回归主要通过 Mockito `JdbcTemplate`、RowMapper 和 SQL/参数断言验证；这不能替代真实 PostgreSQL 驱动下的事务执行验证。
- 仍需在可用 Docker/Testcontainers 环境中确认 Flyway 从历史库升级、`FOR UPDATE SKIP LOCKED` 抢占、父书籍与任务行锁、lease 过期/续租、result/failure 并发幂等，以及 PostgreSQL `TIMESTAMPTZ`/interval 参数行为。
- 历史错误字段未做破坏性迁移；当前依靠写入边界和书籍 API 输出边界共同脱敏。真实 JDBC 联调时应特别检查旧数据、并发 claim/lease 和完整 Spring 容器路径。
