<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useToast } from 'primevue/usetoast';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import InputNumber from 'primevue/inputnumber';
import Textarea from 'primevue/textarea';
import Dropdown from 'primevue/dropdown';
import Checkbox from 'primevue/checkbox';
import Message from 'primevue/message';
import { dispatchPoolsApi } from '@/api/dispatch-pools';
import { applicationsApi, type Application } from '@/api/applications';
import { clientsApi, type Client } from '@/api/clients';

const router = useRouter();
const toast = useToast();

const code = ref('');
const name = ref('');
const description = ref('');
const rateLimit = ref<number>(100);
const concurrency = ref<number>(10);
const applicationId = ref<string | null>(null);
const clientId = ref<string | null>(null);
const isAnchorLevel = ref(false);

const applications = ref<Application[]>([]);
const clients = ref<Client[]>([]);
const loadingApps = ref(true);
const loadingClients = ref(true);
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isCodeValid = computed(() => {
  return !code.value || CODE_PATTERN.test(code.value);
});

const isFormValid = computed(() => {
  return (
    code.value.length >= 2 &&
    code.value.length <= 100 &&
    CODE_PATTERN.test(code.value) &&
    name.value.trim().length > 0 &&
    name.value.length <= 255 &&
    rateLimit.value >= 1 &&
    concurrency.value >= 1 &&
    applicationId.value !== null
  );
});

onMounted(async () => {
  await Promise.all([loadApplications(), loadClients()]);
});

async function loadApplications() {
  loadingApps.value = true;
  try {
    const response = await applicationsApi.list(true);
    applications.value = response.items;
  } catch (e) {
    console.error('Failed to load applications:', e);
  } finally {
    loadingApps.value = false;
  }
}

async function loadClients() {
  loadingClients.value = true;
  try {
    const response = await clientsApi.list('ACTIVE');
    clients.value = response.clients;
  } catch (e) {
    console.error('Failed to load clients:', e);
  } finally {
    loadingClients.value = false;
  }
}

async function onSubmit() {
  if (!isFormValid.value) return;

  submitting.value = true;
  errorMessage.value = null;

  try {
    const pool = await dispatchPoolsApi.create({
      code: code.value,
      name: name.value,
      description: description.value || undefined,
      rateLimit: rateLimit.value,
      concurrency: concurrency.value,
      applicationId: applicationId.value!,
      clientId: isAnchorLevel.value ? undefined : clientId.value || undefined,
    });
    toast.add({ severity: 'success', summary: 'Success', detail: 'Dispatch pool created', life: 3000 });
    router.push(`/dispatch-pools/${pool.id}`);
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Failed to create dispatch pool';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Create Dispatch Pool</h1>
        <p class="page-subtitle">Configure a new rate-limiting pool for dispatch jobs</p>
      </div>
    </header>

    <form @submit.prevent="onSubmit">
      <div class="form-card">
        <div class="form-section">
          <h3>Basic Information</h3>

          <div class="form-field">
            <label>Code <span class="required">*</span></label>
            <InputText
              v-model="code"
              placeholder="pool-code"
              class="full-width"
              :invalid="!!(code && !isCodeValid)"
            />
            <small v-if="code && !isCodeValid" class="p-error">
              Lowercase letters, numbers, hyphens only. Must start with a letter.
            </small>
            <small v-else class="hint">
              Unique identifier for this pool (2-100 characters)
            </small>
          </div>

          <div class="form-field">
            <label>Name <span class="required">*</span></label>
            <InputText
              v-model="name"
              placeholder="Pool display name"
              class="full-width"
              :invalid="name.length > 255"
            />
            <small class="char-count">{{ name.length }} / 255</small>
          </div>

          <div class="form-field">
            <label>Description</label>
            <Textarea
              v-model="description"
              placeholder="Optional description..."
              class="full-width"
              rows="3"
            />
          </div>
        </div>

        <div class="form-section">
          <h3>Rate Limiting</h3>

          <div class="form-row">
            <div class="form-field">
              <label>Rate Limit (per minute) <span class="required">*</span></label>
              <InputNumber
                v-model="rateLimit"
                :min="1"
                class="full-width"
              />
              <small class="hint">Maximum dispatches per minute</small>
            </div>

            <div class="form-field">
              <label>Concurrency <span class="required">*</span></label>
              <InputNumber
                v-model="concurrency"
                :min="1"
                class="full-width"
              />
              <small class="hint">Maximum concurrent dispatches</small>
            </div>
          </div>
        </div>

        <div class="form-section">
          <h3>Scope</h3>

          <div class="form-field">
            <label>Application <span class="required">*</span></label>
            <Dropdown
              v-model="applicationId"
              :options="applications"
              optionLabel="name"
              optionValue="id"
              placeholder="Select an application"
              class="full-width"
              :loading="loadingApps"
              :disabled="loadingApps"
            >
              <template #option="{ option }">
                <div class="dropdown-option">
                  <span class="option-name">{{ option.name }}</span>
                  <span class="option-code">{{ option.code }}</span>
                </div>
              </template>
            </Dropdown>
          </div>

          <div class="form-field">
            <div class="checkbox-field">
              <Checkbox v-model="isAnchorLevel" :binary="true" inputId="anchorLevel" />
              <label for="anchorLevel">Anchor-level pool (not client-scoped)</label>
            </div>
            <small class="hint">
              Anchor-level pools are for dispatch jobs that are not scoped to a specific client.
            </small>
          </div>

          <div class="form-field" v-if="!isAnchorLevel">
            <label>Client</label>
            <Dropdown
              v-model="clientId"
              :options="clients"
              optionLabel="name"
              optionValue="id"
              placeholder="Select a client (optional)"
              class="full-width"
              :loading="loadingClients"
              :disabled="loadingClients"
              showClear
            >
              <template #option="{ option }">
                <div class="dropdown-option">
                  <span class="option-name">{{ option.name }}</span>
                  <span class="option-code">{{ option.identifier }}</span>
                </div>
              </template>
            </Dropdown>
            <small class="hint">
              If specified, this pool will only be used for jobs scoped to this client.
            </small>
          </div>
        </div>

        <Message v-if="errorMessage" severity="error" class="error-message">
          {{ errorMessage }}
        </Message>

        <div class="form-actions">
          <Button
            label="Cancel"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="router.push('/dispatch-pools')"
          />
          <Button
            label="Create Pool"
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

.form-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  padding: 24px;
}

.form-section {
  margin-bottom: 32px;
}

.form-section h3 {
  margin: 0 0 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: #475569;
  text-transform: uppercase;
  letter-spacing: 0.05em;
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

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.full-width {
  width: 100%;
}

.char-count {
  display: block;
  text-align: right;
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.checkbox-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-field label {
  margin: 0;
  cursor: pointer;
}

.dropdown-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.option-name {
  font-weight: 500;
}

.option-code {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
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

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
