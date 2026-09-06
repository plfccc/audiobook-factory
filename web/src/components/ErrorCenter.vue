<script setup lang="ts">
defineProps<{
  errors: string[];
}>();

const emit = defineEmits<{
  clear: [];
}>();
</script>

<template>
  <section class="error-panel panel" aria-labelledby="error-center-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">ATTENTION</p>
        <h2 id="error-center-title">异常中心</h2>
      </div>
      <button v-if="errors.length" type="button" class="text-button" @click="emit('clear')">清空</button>
    </div>
    <div v-if="errors.length" class="error-list">
      <div v-for="(error, index) in errors" :key="`${index}-${error}`" class="error-row">
        <span class="error-dot"></span>
        <span>{{ error }}</span>
      </div>
    </div>
    <p v-else class="empty-state compact-empty">暂无异常记录。</p>
  </section>
</template>
