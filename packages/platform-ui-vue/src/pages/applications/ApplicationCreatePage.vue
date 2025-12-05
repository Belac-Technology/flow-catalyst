<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useToast } from 'primevue/usetoast';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Textarea from 'primevue/textarea';
import Message from 'primevue/message';
import { applicationsApi } from '@/api/applications';

const router = useRouter();
const toast = useToast();

// Form state
const code = ref('');
const name = ref('');
const description = ref('');
const defaultBaseUrl = ref('');
const iconUrl = ref('');

const submitting = ref(false);
const errorMessage = ref<string | null>(null);

// Validation
const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isCodeValid = computed(() => !code.value || CODE_PATTERN.test(code.value));

const isFormValid = computed(() => {
  return (
    code.value &&
    CODE_PATTERN.test(code.value) &&
    name.value.trim().length > 0 &&
    name.value.length <= 100
  );
});

async function onSubmit() {
  if (!isFormValid.value) return;

  submitting.value = true;
  errorMessage.value = null;

  try {
    const application = await applicationsApi.create({
      code: code.value,
      name: name.value,
      description: description.value || undefined,
      defaultBaseUrl: defaultBaseUrl.value || undefined,
      iconUrl: iconUrl.value || undefined,
    });
    toast.add({ severity: 'success', summary: 'Success', detail: 'Application created', life: 3000 });
    router.push(`/applications/${application.id}`);
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Failed to create application';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <Button
        icon="pi pi-arrow-left"
        text
        severity="secondary"
        @click="router.push('/applications')"
        v-tooltip="'Back'"
      />
      <div>
        <h1 class="page-title">Create Application</h1>
        <p class="page-subtitle">Add a new application to the platform</p>
      </div>
    </header>

    <form @submit.prevent="onSubmit">
      <div class="form-card">
        <section class="form-section">
          <h3 class="section-title">Application Identity</h3>

          <div class="form-field">
            <label>Code <span class="required">*</span></label>
            <InputText
              v-model="code"
              placeholder="e.g., inmotion"
              class="full-width"
              :invalid="code && !isCodeValid"
            />
            <small v-if="code && !isCodeValid" class="p-error">
              Must start with a letter, use only lowercase letters, numbers, and hyphens
            </small>
            <small v-else class="hint">
              Unique identifier for the application. Cannot be changed after creation.
            </small>
          </div>

          <div class="form-field">
            <label>Name <span class="required">*</span></label>
            <InputText
              v-model="name"
              placeholder="Human-friendly name"
              class="full-width"
              :invalid="name.length > 100"
            />
            <small class="char-count">{{ name.length }} / 100</small>
          </div>

          <div class="form-field">
            <label>Description</label>
            <Textarea
              v-model="description"
              placeholder="Optional description"
              :rows="3"
              class="full-width"
            />
          </div>
        </section>

        <section class="form-section">
          <h3 class="section-title">Configuration</h3>

          <div class="form-field">
            <label>Default Base URL</label>
            <InputText
              v-model="defaultBaseUrl"
              placeholder="https://example.com"
              class="full-width"
            />
            <small class="hint">Base URL for API calls to this application</small>
          </div>

          <div class="form-field">
            <label>Icon URL</label>
            <InputText
              v-model="iconUrl"
              placeholder="https://example.com/icon.png"
              class="full-width"
            />
            <small class="hint">URL to the application's icon image</small>
          </div>
        </section>

        <Message v-if="errorMessage" severity="error" class="error-message">
          {{ errorMessage }}
        </Message>

        <div class="form-actions">
          <Button
            label="Cancel"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="router.push('/applications')"
          />
          <Button
            label="Create Application"
            icon="pi pi-check"
            type="submit"
            :loading="submitting"
            :disabled="!isFormValid"
          />
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 700px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.form-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  padding: 24px;
}

.form-section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
}

.form-field {
  margin-bottom: 20px;
}

.form-field > label {
  display: block;
  font-weight: 500;
  margin-bottom: 6px;
}

.form-field .required {
  color: #ef4444;
}

.full-width {
  width: 100%;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.char-count {
  display: block;
  text-align: right;
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.error-message {
  margin-bottom: 16px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}
</style>
