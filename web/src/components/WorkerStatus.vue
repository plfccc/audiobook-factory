<script setup lang="ts">
import { computed } from "vue";
import type { WorkerStatus as WorkerStatusData } from "../types";

const props = defineProps<{
  errorCount: number;
  workerStatus: WorkerStatusData | null;
  statusError: string;
}>();

const statusMeta = computed(() => {
  switch (props.workerStatus?.status) {
    case "ONLINE":
      return { label: "在线", className: "status-pill-online" };
    case "OFFLINE":
      return { label: "已离线", className: "status-pill-offline" };
    case "EXPIRED":
      return { label: "已过期", className: "status-pill-expired" };
    default:
      return { label: "未连接", className: "status-pill-muted" };
  }
});

function nestedRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

const runtime = computed(() => nestedRecord(props.workerStatus?.capabilities?.runtime));
const modelCapabilities = computed(() => nestedRecord(props.workerStatus?.capabilities?.capabilities));

const workerName = computed(() => props.workerStatus?.name || "未连接");
const gpuName = computed(() => String(runtime.value.gpuName || "未探测"));
const modelName = computed(() => String(
  modelCapabilities.value.modelId || runtime.value.modelId || "未选择",
));
const lastHeartbeat = computed(() => {
  if (!props.workerStatus?.lastHeartbeatAt) return "—";
  return new Date(props.workerStatus.lastHeartbeatAt).toLocaleString("zh-CN");
});
</script>

<template>
  <section class="status-card panel" aria-labelledby="worker-status-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">RUNTIME</p>
        <h2 id="worker-status-title">运行状态</h2>
      </div>
      <span class="status-pill" :class="statusMeta.className">{{ statusMeta.label }}</span>
    </div>
    <div class="status-grid">
      <div class="status-item">
        <span class="status-label">Worker</span>
        <strong>{{ workerName }}</strong>
      </div>
      <div class="status-item">
        <span class="status-label">推理通道</span>
        <strong>Colab GPU / Qwen TTS</strong>
      </div>
      <div class="status-item">
        <span class="status-label">GPU</span>
        <strong>{{ gpuName }}</strong>
      </div>
      <div class="status-item">
        <span class="status-label">模型</span>
        <strong class="status-value-truncate" :title="modelName">{{ modelName }}</strong>
      </div>
      <div class="status-item">
        <span class="status-label">最后心跳</span>
        <strong>{{ lastHeartbeat }}</strong>
      </div>
    </div>
    <p v-if="statusError" class="helper-text status-error">状态查询失败：{{ statusError }}</p>
    <p v-else class="helper-text">
      每 15 秒刷新一次；Colab 运行时被回收或停止后，超过 90 秒会显示为离线。当前异常 {{ errorCount }} 条。
    </p>
  </section>
</template>
