<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ApiError,
  AuthRequiredError,
  getAccessToken,
  getBookProgress,
  getTtsModels,
  getTtsPresets,
  listBooks,
  listChapters,
  listSegments,
  pauseBook,
  resumeBook,
  setAccessToken,
  uploadEpub,
} from "./api";
import BookList from "./components/BookList.vue";
import BookProgress from "./components/BookProgress.vue";
import ChapterTable from "./components/ChapterTable.vue";
import ErrorCenter from "./components/ErrorCenter.vue";
import PreviewPanel from "./components/PreviewPanel.vue";
import WorkerStatus from "./components/WorkerStatus.vue";
import type { BookSummary, Chapter, ChapterProgress, Segment, TtsModel, TtsPreset } from "./types";

const books = ref<BookSummary[]>([]);
const selectedBookId = ref<number | null>(null);
const progress = ref<ChapterProgress | null>(null);
const chapters = ref<Chapter[]>([]);
const selectedChapter = ref<Chapter | null>(null);
const segments = ref<Segment[]>([]);
const models = ref<TtsModel[]>([]);
const presets = ref<TtsPreset[]>([]);
const loadingBooks = ref(false);
const loadingBook = ref(false);
const loadingSegments = ref(false);
const importing = ref(false);
const actionBusy = ref(false);
const notice = ref("");
const errors = ref<string[]>([]);
const authRequired = ref(false);
const accessTokenDraft = ref(getAccessToken());

const selectedBook = computed(() => books.value.find((book) => book.id === selectedBookId.value) ?? null);
const hasRunningBook = computed(() => selectedBook.value?.status === "RUNNING");
const hasPausedBook = computed(() => selectedBook.value?.status === "PAUSED");

function messageFrom(error: unknown): string {
  if (error instanceof ApiError) {
    return error.code ? `${error.code}：${error.message}` : error.message;
  }
  return error instanceof Error ? error.message : "请求失败，请查看服务端日志。";
}

function reportError(error: unknown): void {
  if (error instanceof AuthRequiredError || (error instanceof ApiError && error.status === 401)) {
    authRequired.value = true;
  }
  errors.value = [messageFrom(error), ...errors.value].slice(0, 5);
}

async function loadBookData(bookId: number): Promise<void> {
  loadingBook.value = true;
  progress.value = null;
  chapters.value = [];
  selectedChapter.value = null;
  segments.value = [];
  try {
    const [nextProgress, nextChapters] = await Promise.all([
      getBookProgress(bookId),
      listChapters(bookId),
    ]);
    progress.value = nextProgress;
    chapters.value = nextChapters;
  } catch (error) {
    reportError(error);
  } finally {
    loadingBook.value = false;
  }
}

async function loadBooks(preferredBookId?: number): Promise<void> {
  loadingBooks.value = true;
  try {
    const nextBooks = await listBooks();
    books.value = nextBooks;
    const nextId = preferredBookId ?? selectedBookId.value ?? nextBooks[0]?.id ?? null;
    selectedBookId.value = nextBooks.some((book) => book.id === nextId) ? nextId : nextBooks[0]?.id ?? null;
    if (selectedBookId.value !== null) {
      await loadBookData(selectedBookId.value);
    }
  } catch (error) {
    reportError(error);
  } finally {
    loadingBooks.value = false;
  }
}

async function loadRuntimeOptions(): Promise<void> {
  const [modelResult, presetResult] = await Promise.allSettled([getTtsModels(), getTtsPresets()]);
  if (modelResult.status === "fulfilled") models.value = modelResult.value;
  else reportError(modelResult.reason);
  if (presetResult.status === "fulfilled") presets.value = presetResult.value;
  else reportError(presetResult.reason);
}

async function loadDashboard(): Promise<void> {
  await Promise.all([loadBooks(), loadRuntimeOptions()]);
}

async function selectBook(bookId: number): Promise<void> {
  if (bookId === selectedBookId.value && chapters.value.length) return;
  selectedBookId.value = bookId;
  await loadBookData(bookId);
}

async function selectChapter(chapter: Chapter): Promise<void> {
  if (!selectedBookId.value) return;
  selectedChapter.value = chapter;
  segments.value = [];
  loadingSegments.value = true;
  try {
    segments.value = await listSegments(selectedBookId.value, chapter.id);
  } catch (error) {
    reportError(error);
  } finally {
    loadingSegments.value = false;
  }
}

async function refreshSelectedBook(): Promise<void> {
  if (!selectedBookId.value) return;
  await loadBooks(selectedBookId.value);
}

async function onImportChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".epub")) {
    errors.value = ["只支持导入 .epub 文件。", ...errors.value].slice(0, 5);
    return;
  }
  importing.value = true;
  notice.value = "正在解析 EPUB…";
  try {
    const result = await uploadEpub(file);
    notice.value = `已导入 ${file.name}，共识别 ${result.chapterCount ?? "若干"} 章。`;
    await loadBooks(Number(result.bookId));
  } catch (error) {
    notice.value = "";
    reportError(error);
  } finally {
    importing.value = false;
  }
}

async function toggleBookStatus(): Promise<void> {
  if (!selectedBookId.value || actionBusy.value) return;
  actionBusy.value = true;
  try {
    if (hasRunningBook.value) {
      await pauseBook(selectedBookId.value);
      notice.value = "已暂停当前书籍，未完成章节会保留在队列中。";
    } else if (hasPausedBook.value) {
      await resumeBook(selectedBookId.value);
      notice.value = "已恢复当前书籍。";
    }
    await refreshSelectedBook();
  } catch (error) {
    reportError(error);
  } finally {
    actionBusy.value = false;
  }
}

function saveToken(): void {
  setAccessToken(accessTokenDraft.value);
  authRequired.value = false;
  notice.value = "访问令牌已保存到当前浏览器会话。";
  void loadDashboard();
}

function clearErrors(): void {
  errors.value = [];
}

function onJobSubmitted(batch: { type: string; chapterStart: number; chapterEnd: number }): void {
  notice.value = `${batch.type === "PREVIEW" ? "试听" : "生成"}任务已提交：第 ${batch.chapterStart}～${batch.chapterEnd} 章。`;
  void refreshSelectedBook();
}

function onJobError(message: string): void {
  errors.value = [message, ...errors.value].slice(0, 5);
}

onMounted(() => {
  void loadDashboard();
});
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="Audiobook Factory 首页">
        <span class="brand-mark">AF</span>
        <span>
          <strong>Audiobook Factory</strong>
          <small>单用户有声书工作台</small>
        </span>
      </a>
      <div class="topbar-actions">
        <label class="button button-primary upload-button" :class="{ disabled: importing }">
          <input type="file" accept=".epub,application/epub+zip" :disabled="importing" @change="onImportChange" />
          {{ importing ? "导入中…" : "导入 EPUB" }}
        </label>
        <button type="button" class="button button-ghost" :disabled="loadingBooks" @click="loadDashboard">刷新</button>
      </div>
    </header>

    <main class="page-content">
      <section class="hero-block">
        <div>
          <p class="eyebrow">PERSONAL AUDIO PIPELINE</p>
          <h1>把长篇小说，变成可以持续收听的章节。</h1>
          <p class="hero-copy">控制中心负责书籍、章节、任务和断点；Colab Worker 负责模型与 GPU。每章完成即可发布，不必等待全书结束。</p>
        </div>
        <div class="hero-note">
          <span class="hero-note-label">当前工作方式</span>
          <strong>章节是最小进度节点</strong>
          <span>失败可重试，暂停后可继续。</span>
        </div>
      </section>

      <section v-if="authRequired" class="auth-panel panel" aria-labelledby="auth-title">
        <div>
          <p class="eyebrow">CONTROL CENTER ACCESS</p>
          <h2 id="auth-title">需要访问令牌</h2>
          <p class="helper-text">令牌只保存在当前浏览器会话，不会写入书籍配置或日志。</p>
        </div>
        <form class="token-form" @submit.prevent="saveToken">
          <input v-model="accessTokenDraft" type="password" autocomplete="off" placeholder="粘贴 APP_ACCESS_TOKEN" />
          <button type="submit" class="button button-primary">保存并重试</button>
        </form>
      </section>

      <div v-if="notice" class="notice-banner" role="status">{{ notice }}</div>

      <div class="dashboard-grid">
        <aside class="sidebar-column">
          <BookList :books="books" :selected-book-id="selectedBookId" :loading="loadingBooks" @select="selectBook" />
          <div class="sidebar-hint">
            <strong>使用提示</strong>
            <p>首次运行先导入 EPUB，再选择章节试听；正式生成前可以重复调整声音配置。</p>
          </div>
        </aside>

        <section class="workspace-column">
          <WorkerStatus :error-count="errors.length" />
          <BookProgress :book="selectedBook" :progress="progress" :loading="loadingBook" />

          <section v-if="selectedBook" class="book-toolbar panel">
            <div>
              <p class="eyebrow">ACTIVE BOOK</p>
              <h2>{{ selectedBook.title }}</h2>
              <p class="helper-text">{{ selectedBook.author || "未知作者" }} · {{ selectedBook.chapterCount }} 章</p>
            </div>
            <button
              v-if="hasRunningBook || hasPausedBook"
              type="button"
              class="button"
              :class="hasRunningBook ? 'button-danger' : 'button-primary'"
              :disabled="actionBusy"
              @click="toggleBookStatus"
            >
              {{ actionBusy ? "处理中…" : hasRunningBook ? "暂停生成" : "继续生成" }}
            </button>
          </section>

          <ChapterTable
            :chapters="chapters"
            :selected-chapter-id="selectedChapter?.id ?? null"
            :segments="segments"
            :loading="loadingBook"
            :segments-loading="loadingSegments"
            @select="selectChapter"
          />
          <PreviewPanel
            :book-id="selectedBookId"
            :selected-chapter="selectedChapter"
            :models="models"
            :presets="presets"
            @submitted="onJobSubmitted"
            @error="onJobError"
          />
          <ErrorCenter :errors="errors" @clear="clearErrors" />
        </section>
      </div>
    </main>
  </div>
</template>
