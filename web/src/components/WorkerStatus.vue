<script setup lang="ts">
defineProps<{
  errorCount: number;
}>();
</script>

<template>
  <section class="status-card panel" aria-labelledby="worker-status-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">RUNTIME</p>
        <h2 id="worker-status-title">运行状态</h2>
      </div>
      <span class="status-pill status-pill-muted">待接入</span>
    </div>
    <div class="status-grid">
      <div class="status-item">
        <span class="status-label">Worker</span>
        <strong>未连接</strong>
      </div>
      <div class="status-item">
        <span class="status-label">Google AI Studio</span>
        <strong>由 Colab Worker 管理</strong>
      </div>
      <div class="status-item">
        <span class="status-label">GPU</span>
        <strong>由 Worker 自动探测</strong>
      </div>
      <div class="status-item">
        <span class="status-label">磁盘</span>
        <strong>待接入</strong>
      </div>
      <div class="status-item">
        <span class="status-label">异常</span>
        <strong>{{ errorCount }} 条</strong>
      </div>
    </div>
    <p class="helper-text">
      当前控制中心还没有运行态查询接口，因此不显示伪造的在线状态或显卡信息。
    </p>
  </section>
</template>
