<script setup lang="ts">
import type { BookSummary } from "../types";

defineProps<{
  books: BookSummary[];
  selectedBookId: number | null;
  loading?: boolean;
}>();

const emit = defineEmits<{
  select: [bookId: number];
}>();

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    DRAFT: "草稿",
    READY: "待运行",
    RUNNING: "生成中",
    PAUSED: "已暂停",
    COMPLETED: "已完成",
    FAILED: "失败",
  };
  return labels[status] ?? status;
}
</script>

<template>
  <section class="book-list panel" aria-labelledby="book-list-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">LIBRARY</p>
        <h2 id="book-list-title">书籍</h2>
      </div>
      <span class="count-badge">{{ books.length }}</span>
    </div>

    <div v-if="loading && books.length === 0" class="empty-state">正在读取书籍列表…</div>
    <div v-else-if="books.length === 0" class="empty-state">还没有书籍，请从右上角导入 EPUB。</div>
    <div v-else class="book-items">
      <button
        v-for="book in books"
        :key="book.id"
        type="button"
        class="book-item"
        :class="{ selected: book.id === selectedBookId }"
        @click="emit('select', book.id)"
      >
        <span class="book-item-main">
          <strong>{{ book.title }}</strong>
          <small>{{ book.author || "未知作者" }}</small>
        </span>
        <span class="book-item-side">
          <span class="book-chapter-count">已完成 {{ book.completedChapters }} 章 · 共 {{ book.chapterCount }} 章</span>
          <span class="status-text">{{ statusLabel(book.status) }}</span>
        </span>
      </button>
    </div>
  </section>
</template>
