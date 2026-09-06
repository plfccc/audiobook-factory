<script setup lang="ts">
import { computed } from "vue";
import type { BookSummary, ChapterProgress } from "../types";

const props = defineProps<{
  book: BookSummary | null;
  progress: ChapterProgress | null;
  loading?: boolean;
}>();

const completed = computed(() => props.progress?.completedChapters ?? props.book?.completedChapters ?? 0);
const total = computed(() => props.progress?.totalChapters ?? props.book?.chapterCount ?? 0);
const percent = computed(() => {
  if (total.value <= 0) return 0;
  return Math.min(100, Math.round((completed.value / total.value) * 1000) / 10);
});
const currentChapter = computed(() => props.progress?.currentChapter ?? null);
const currentTitle = computed(() => props.progress?.currentChapterTitle ?? "");

function statusLabel(status?: string | null): string {
  const labels: Record<string, string> = {
    DRAFT: "草稿",
    READY: "待运行",
    RUNNING: "生成中",
    PAUSED: "已暂停",
    COMPLETED: "已完成",
    FAILED: "失败",
  };
  return labels[status ?? ""] ?? status ?? "未知";
}
</script>

<template>
  <section class="progress-card panel" aria-labelledby="book-progress-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">BOOK PROGRESS</p>
        <h2 id="book-progress-title">生成进度</h2>
      </div>
      <span v-if="book" class="status-pill" :class="`status-${String(book.status).toLowerCase()}`">
        {{ statusLabel(progress?.status ?? book.status) }}
      </span>
    </div>

    <div v-if="book" class="progress-summary">
      <div class="progress-number">{{ completed }} / {{ total }} 章</div>
      <div class="progress-percent">{{ percent }}%</div>
    </div>
    <div v-else class="empty-state">先导入一本 EPUB，开始建立章节进度。</div>

    <div class="progress-track" aria-label="章节完成进度">
      <div class="progress-fill" :style="{ width: `${percent}%` }"></div>
    </div>
    <div class="progress-meta">
      <span v-if="currentChapter">当前：第 {{ currentChapter }} 章<span v-if="currentTitle"> · {{ currentTitle }}</span></span>
      <span v-else>当前：尚未开始</span>
      <span v-if="loading" class="loading-text">正在同步</span>
    </div>
  </section>
</template>
