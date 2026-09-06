<script setup lang="ts">
import { computed } from "vue";
import type { Chapter, Segment } from "../types";

const props = defineProps<{
  chapters: Chapter[];
  selectedChapterId: number | null;
  segments: Segment[];
  loading?: boolean;
  segmentsLoading?: boolean;
}>();

const emit = defineEmits<{
  select: [chapter: Chapter];
}>();

const selectedChapter = computed(() =>
  props.chapters.find((chapter) => chapter.id === props.selectedChapterId) ?? null,
);

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: "待生成",
    WAITING: "排队中",
    RUNNING: "生成中",
    SUCCESS: "已完成",
    FAILED: "失败",
    PAUSED: "已暂停",
  };
  return labels[status] ?? status;
}

function progressPercent(chapter: Chapter): number {
  if (!chapter.segmentCount) return chapter.status === "SUCCESS" ? 100 : 0;
  return Math.round((chapter.completedSegments / chapter.segmentCount) * 100);
}

function segmentPreview(text: string): string {
  const compact = text.replace(/\s+/g, " ").trim();
  return compact.length > 96 ? `${compact.slice(0, 96)}…` : compact;
}
</script>

<template>
  <section class="chapter-panel panel" aria-labelledby="chapter-list-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">CHAPTERS</p>
        <h2 id="chapter-list-title">章节</h2>
      </div>
      <span class="count-badge">{{ chapters.length }}</span>
    </div>

    <div v-if="loading" class="empty-state">正在读取章节…</div>
    <div v-else-if="chapters.length === 0" class="empty-state">这本书还没有可用章节。</div>
    <div v-else class="chapter-table-wrap">
      <table class="chapter-table">
        <thead>
          <tr>
            <th>章节</th>
            <th>状态</th>
            <th>完成度</th>
            <th>音频</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="chapter in chapters"
            :key="chapter.id"
            :class="{ selected: chapter.id === selectedChapterId }"
          >
            <td>
              <button type="button" class="chapter-link" @click="emit('select', chapter)">
                <span>第 {{ chapter.chapterNumber }} 章</span>
                <strong>{{ chapter.title }}</strong>
              </button>
            </td>
            <td><span class="status-text">{{ statusLabel(chapter.status) }}</span></td>
            <td>
              <span>{{ chapter.completedSegments }} / {{ chapter.segmentCount }}</span>
              <span class="mini-progress-track"><span :style="{ width: `${progressPercent(chapter)}%` }"></span></span>
            </td>
            <td>
              <template v-if="chapter.audioUrl">
                <a class="audio-link" :href="chapter.audioUrl" target="_blank" rel="noreferrer">播放</a>
                <a v-if="chapter.audioDownloadUrl" class="audio-link" :href="chapter.audioDownloadUrl">下载</a>
              </template>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <section v-if="selectedChapter" class="segment-detail" aria-labelledby="segment-detail-title">
      <div class="section-heading compact-heading">
        <div>
          <p class="eyebrow">SELECTED CHAPTER</p>
          <h3 id="segment-detail-title">第 {{ selectedChapter.chapterNumber }} 章 · {{ selectedChapter.title }}</h3>
        </div>
        <span class="status-pill status-pill-muted">{{ selectedChapter.completedSegments }} / {{ selectedChapter.segmentCount }}</span>
      </div>
      <audio v-if="selectedChapter.audioUrl" class="chapter-audio" controls preload="none" :src="selectedChapter.audioUrl">
        当前浏览器不支持音频播放。
      </audio>

      <div v-if="segmentsLoading" class="empty-state">正在读取当前章节明细…</div>
      <div v-else-if="segments.length === 0" class="empty-state">当前章节还没有生成明细。</div>
      <div v-else class="segment-list">
        <article v-for="segment in segments" :key="segment.jobId" class="segment-item">
          <div class="segment-index">{{ String(segment.segmentIndex + 1).padStart(2, "0") }}</div>
          <div class="segment-copy">
            <div class="segment-title-row">
              <strong>{{ statusLabel(segment.status) }}</strong>
              <span v-if="segment.attempts">尝试 {{ segment.attempts }} 次</span>
            </div>
            <p>{{ segmentPreview(segment.text) }}</p>
            <small v-if="segment.errorMessage" class="error-text">{{ segment.errorMessage }}</small>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>
