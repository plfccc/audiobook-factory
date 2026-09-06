export type BookStatus = "DRAFT" | "READY" | "RUNNING" | "PAUSED" | "COMPLETED" | "FAILED" | string;

export interface BookSummary {
  id: number;
  title: string;
  author?: string | null;
  status: BookStatus;
  chapterCount: number;
  completedChapters: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface BookDetail extends BookSummary {
  bookVersionId?: number | string | null;
  sourceSha256?: string | null;
}

export interface Chapter {
  id: number;
  chapterNumber: number;
  title: string;
  status: string;
  segmentCount: number;
  completedSegments: number;
  audioUrl?: string | null;
  audioDownloadUrl?: string | null;
}

export interface Segment {
  jobId: string | number;
  chapterNumber: number;
  segmentIndex: number;
  text: string;
  textSha256?: string | null;
  status: string;
  attempts?: number;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export interface ChapterProgress {
  bookId?: number;
  completedChapters: number;
  totalChapters: number;
  currentChapter?: number | null;
  currentChapterTitle?: string | null;
  status?: BookStatus | null;
}

export interface TtsCapabilities {
  languages?: string[];
  voiceDesign?: boolean;
  voiceClone?: boolean;
  emotionControl?: boolean;
  durationControl?: boolean;
}

export interface TtsModel {
  engineId: string;
  modelId: string;
  modelVersion: string;
  minimumVramBytes: number;
  priority: number;
  capabilities: TtsCapabilities;
  licenseUrl?: string | null;
}

export interface TtsPreset {
  id: number;
  engine: string;
  model: string;
  modelVersion: string;
  styleInstruction?: string | null;
  speed: number;
  modelParameters?: Record<string, unknown>;
  segmentLength: number;
  voice?: string;
  language?: string;
  voiceProfileId?: string | number | null;
}

export interface JobBatch {
  bookId: number;
  jobIds: string[];
  chapterStart: number;
  chapterEnd: number;
  type: string;
  status: string;
  runId?: string | null;
  batchId?: string | null;
  scopeId?: string | null;
}

export interface JobRequest {
  chapterNumber?: number;
  chapterStart?: number;
  chapterEnd?: number;
  segmentIndex?: number;
  preset?: Record<string, unknown>;
  runId?: string;
  batchId?: string;
  scopeId?: string;
}

export type WorkerRuntimeStatus = "NOT_CONNECTED" | "ONLINE" | "OFFLINE" | "EXPIRED" | string;

export interface WorkerStatus {
  status: WorkerRuntimeStatus;
  workerId?: string | null;
  name?: string | null;
  lastHeartbeatAt?: string | null;
  updatedAt?: string | null;
  capabilities?: Record<string, unknown> | null;
}

export interface ApiErrorShape {
  code?: string;
  message?: string;
  error?: string;
}
