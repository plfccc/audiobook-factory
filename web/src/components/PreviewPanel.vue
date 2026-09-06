<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { startGeneration, startPreview } from "../api";
import type { Chapter, JobBatch, TtsModel, TtsPreset } from "../types";

const props = defineProps<{
  bookId: number | null;
  selectedChapter: Chapter | null;
  models: TtsModel[];
  presets: TtsPreset[];
}>();

const emit = defineEmits<{
  submitted: [batch: JobBatch];
  error: [message: string];
}>();

const selectedModelId = ref("");
const selectedPresetId = ref("");
const voice = ref("default");
const language = ref("zh-CN");
const stylePrompt = ref("自然、清晰、语速稳定地朗读，不添加额外内容。");
const referenceText = ref("");
const referenceFileName = ref("");
const segmentIndex = ref(1);
const busy = ref(false);

const selectedModel = computed(() => props.models.find((model) => model.modelId === selectedModelId.value) ?? null);
const selectedPreset = computed(() => props.presets.find((preset) => String(preset.id) === selectedPresetId.value) ?? null);

watch(
  () => props.models,
  (models) => {
    if (!models.some((model) => model.modelId === selectedModelId.value)) {
      selectedModelId.value = models[0]?.modelId ?? "";
    }
  },
  { immediate: true },
);

watch(
  () => props.presets,
  (presets) => {
    if (!presets.some((preset) => String(preset.id) === selectedPresetId.value)) {
      selectedPresetId.value = presets[0] ? String(presets[0].id) : "";
    }
  },
  { immediate: true },
);

watch(selectedPreset, (preset) => {
  if (!preset) return;
  voice.value = preset.voice ?? voice.value;
  stylePrompt.value = preset.styleInstruction ?? stylePrompt.value;
  language.value = preset.language ?? language.value;
  if (preset.model && !selectedModelId.value) selectedModelId.value = preset.model;
});

function buildPreset(): Record<string, unknown> {
  const preset = selectedPreset.value;
  const model = selectedModel.value;
  const modelId = model?.modelId ?? preset?.model ?? "Qwen/Qwen3-TTS-12Hz-1.7B-Base";
  const modelVersion = model?.modelVersion ?? preset?.modelVersion ?? "1.0";
  const parameters = preset?.modelParameters ?? {};
  return {
    provider: preset?.engine ?? model?.engineId ?? "qwen3-tts",
    engine: preset?.engine ?? model?.engineId ?? "qwen3-tts",
    model: modelId,
    modelVersion,
    voice: voice.value.trim() || "default",
    language: language.value.trim() || "zh-CN",
    outputFormat: "wav",
    stylePrompt: stylePrompt.value.trim(),
    style_prompt: stylePrompt.value.trim(),
    modelParameters: parameters,
    parameters,
    segmentTargetChars: preset?.segmentLength ?? 220,
    segmentMaxChars: Math.max(preset?.segmentLength ?? 320, 320),
    ...(preset?.voiceProfileId ? { voiceProfileId: preset.voiceProfileId } : {}),
  };
}

function handleReferenceFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  referenceFileName.value = input.files?.[0]?.name ?? "";
}

async function submit(kind: "preview" | "generation"): Promise<void> {
  if (!props.bookId || !props.selectedChapter || busy.value) return;
  busy.value = true;
  try {
    const body = {
      chapterNumber: props.selectedChapter.chapterNumber,
      ...(kind === "preview" ? { segmentIndex: Math.max(1, segmentIndex.value) } : {}),
      preset: buildPreset(),
    };
    const batch = kind === "preview"
      ? await startPreview(props.bookId, body)
      : await startGeneration(props.bookId, {
          ...body,
          chapterStart: props.selectedChapter.chapterNumber,
          chapterEnd: props.selectedChapter.chapterNumber,
        });
    emit("submitted", batch);
  } catch (error) {
    emit("error", error instanceof Error ? error.message : "任务提交失败");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="preview-panel panel" aria-labelledby="preview-panel-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">VOICE LAB</p>
        <h2 id="preview-panel-title">试听与生成</h2>
      </div>
      <span class="status-pill status-pill-muted">章节级</span>
    </div>

    <div v-if="!selectedChapter" class="empty-state">请先从章节列表选择章节，再配置试听或生成。</div>
    <form v-else class="preview-form" @submit.prevent>
      <div class="form-grid">
        <label>
          <span>模型</span>
          <select v-model="selectedModelId">
            <option value="">使用 Worker 默认模型</option>
            <option v-for="model in models" :key="model.modelId" :value="model.modelId">{{ model.modelId }}</option>
          </select>
        </label>
        <label>
          <span>Preset</span>
          <select v-model="selectedPresetId">
            <option value="">临时配置</option>
            <option v-for="preset in presets" :key="preset.id" :value="String(preset.id)">
              {{ preset.engine }} / {{ preset.model }}
            </option>
          </select>
        </label>
        <label>
          <span>语言</span>
          <input v-model="language" type="text" placeholder="zh-CN" />
        </label>
        <label>
          <span>声音标识</span>
          <input v-model="voice" type="text" placeholder="default" />
        </label>
        <label>
          <span>试听片段序号（从 1 开始）</span>
          <input v-model.number="segmentIndex" type="number" min="1" step="1" />
        </label>
        <label>
          <span>参考音频</span>
          <input type="file" accept="audio/*" @change="handleReferenceFile" />
          <small v-if="referenceFileName" class="muted">已选择：{{ referenceFileName }}；素材上传接口待接入。</small>
          <small v-else class="muted">素材上传接口待接入，当前不会把本机路径发送到服务器。</small>
        </label>
      </div>

      <label>
        <span>声音参考文本</span>
        <textarea v-model="referenceText" rows="2" placeholder="参考音频对应的文字（接口接入后用于音色克隆）"></textarea>
      </label>
      <label>
        <span>风格提示</span>
        <textarea v-model="stylePrompt" rows="3"></textarea>
      </label>

      <p class="helper-text">
        试听只提交当前章节的一个任务；确认音色后再提交整章生成。参考音频字段先保留在界面，待控制中心提供素材接口后启用。
      </p>
      <div class="action-row">
        <button type="button" class="button button-secondary" :disabled="busy" @click="submit('preview')">
          {{ busy ? "提交中…" : "生成试听" }}
        </button>
        <button type="button" class="button button-primary" :disabled="busy" @click="submit('generation')">生成本章</button>
      </div>
    </form>
  </section>
</template>
