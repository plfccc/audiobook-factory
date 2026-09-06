import type {
  BookDetail,
  BookSummary,
  Chapter,
  ChapterProgress,
  JobBatch,
  JobRequest,
  Segment,
  TtsModel,
  TtsPreset,
  WorkerStatus,
} from "./types";

const ACCESS_TOKEN_KEY = "audiobook_factory_access_token";

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export class AuthRequiredError extends ApiError {
  constructor(message = "需要访问令牌") {
    super(message, 401, "AUTH_REQUIRED");
    this.name = "AuthRequiredError";
  }
}

export function getAccessToken(): string {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? "";
}

export function setAccessToken(token: string): void {
  const value = token.trim();
  if (value) {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, value);
  } else {
    sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  }
}

function apiUrl(path: string): string {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? "";
  return `${base}${path}`;
}

async function parseError(response: Response): Promise<ApiError> {
  let payload: { code?: string; message?: string; error?: string } = {};
  try {
    payload = (await response.json()) as { code?: string; message?: string; error?: string };
  } catch {
    // 非 JSON 响应仍然转换为统一的 API 错误。
  }
  const message = payload.message ?? payload.error ?? `请求失败（${response.status}）`;
  return response.status === 401
    ? new AuthRequiredError(message)
    : new ApiError(message, response.status, payload.code);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let body = init.body;
  if (body && typeof body === "object" && !(body instanceof FormData) && !(body instanceof Blob)) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(body);
  }

  const response = await fetch(apiUrl(path), { ...init, headers, body });
  if (!response.ok) {
    throw await parseError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function listBooks(): Promise<BookSummary[]> {
  return request<BookSummary[]>("/api/v1/books");
}

export function getBook(bookId: number): Promise<BookDetail> {
  return request<BookDetail>(`/api/v1/books/${bookId}`);
}

export function getBookProgress(bookId: number): Promise<ChapterProgress> {
  return request<ChapterProgress>(`/api/v1/books/${bookId}/progress`);
}

export function listChapters(bookId: number): Promise<Chapter[]> {
  return request<Chapter[]>(`/api/v1/books/${bookId}/chapters`);
}

export function listSegments(bookId: number, chapterId: number): Promise<Segment[]> {
  return request<Segment[]>(`/api/v1/books/${bookId}/chapters/${chapterId}/segments`);
}

export function uploadEpub(file: File): Promise<{ bookId: number; chapterCount: number; [key: string]: unknown }> {
  const formData = new FormData();
  formData.append("file", file, file.name);
  return request<{ bookId: number; chapterCount: number; [key: string]: unknown }>("/api/v1/books", {
    method: "POST",
    body: formData,
  });
}

export function startPreview(bookId: number, body: JobRequest): Promise<JobBatch> {
  return request<JobBatch>(`/api/v1/books/${bookId}/preview`, {
    method: "POST",
    body: body as unknown as BodyInit,
  });
}

export function startGeneration(bookId: number, body: JobRequest): Promise<JobBatch> {
  return request<JobBatch>(`/api/v1/books/${bookId}/generation`, {
    method: "POST",
    body: body as unknown as BodyInit,
  });
}

export function pauseBook(bookId: number): Promise<void> {
  return request<void>(`/api/v1/books/${bookId}/pause`, { method: "POST" });
}

export function resumeBook(bookId: number): Promise<void> {
  return request<void>(`/api/v1/books/${bookId}/resume`, { method: "POST" });
}

export function getTtsModels(): Promise<TtsModel[]> {
  return request<TtsModel[]>("/api/v1/tts/models");
}

export function getTtsPresets(): Promise<TtsPreset[]> {
  return request<TtsPreset[]>("/api/v1/tts/presets");
}

export function getWorkerStatus(): Promise<WorkerStatus> {
  return request<WorkerStatus>("/api/v1/worker-status");
}
