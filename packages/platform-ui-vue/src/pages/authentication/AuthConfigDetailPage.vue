<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';
import ProgressSpinner from 'primevue/progressspinner';
import Message from 'primevue/message';
import Dialog from 'primevue/dialog';
import Checkbox from 'primevue/checkbox';
import { useToast } from 'primevue/usetoast';
import { authConfigsApi, type AuthConfig } from '@/api/auth-configs';
import SecretRefInput from '@/components/SecretRefInput.vue';

const route = useRoute();
const router = useRouter();
const toast = useToast();

const config = ref<AuthConfig | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

// Edit state
const isEditing = ref(false);
const editForm = ref({
  oidcIssuerUrl: '',
  oidcClientId: '',
  oidcClientSecretRef: '',
  oidcMultiTenant: false,
  oidcIssuerPattern: '',
});
const editLoading = ref(false);
const editError = ref<string | null>(null);

// Secret validation state
const showValidateDialog = ref(false);
const secretRefToValidate = ref('');
const validateLoading = ref(false);
const validateResult = ref<{ valid: boolean; message: string } | null>(null);

// Delete state
const showDeleteDialog = ref(false);
const deleteLoading = ref(false);

const configId = computed(() => route.params.id as string);

onMounted(async () => {
  await loadConfig();
});

async function loadConfig() {
  loading.value = true;
  error.value = null;
  try {
    config.value = await authConfigsApi.get(configId.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load auth configuration';
  } finally {
    loading.value = false;
  }
}

function startEdit() {
  if (!config.value) return;
  editForm.value = {
    oidcIssuerUrl: config.value.oidcIssuerUrl || '',
    oidcClientId: config.value.oidcClientId || '',
    oidcClientSecretRef: '',
    oidcMultiTenant: config.value.oidcMultiTenant || false,
    oidcIssuerPattern: config.value.oidcIssuerPattern || '',
  };
  editError.value = null;
  isEditing.value = true;
}

function cancelEdit() {
  isEditing.value = false;
  editError.value = null;
}

async function saveEdit() {
  if (!config.value) return;

  if (!editForm.value.oidcIssuerUrl.trim()) {
    editError.value = 'OIDC Issuer URL is required';
    return;
  }
  if (!editForm.value.oidcClientId.trim()) {
    editError.value = 'OIDC Client ID is required';
    return;
  }

  editLoading.value = true;
  editError.value = null;

  try {
    const updated = await authConfigsApi.updateOidc(config.value.id, {
      oidcIssuerUrl: editForm.value.oidcIssuerUrl.trim(),
      oidcClientId: editForm.value.oidcClientId.trim(),
      oidcClientSecretRef: editForm.value.oidcClientSecretRef.trim() || undefined,
      oidcMultiTenant: editForm.value.oidcMultiTenant,
      oidcIssuerPattern: editForm.value.oidcIssuerPattern.trim() || undefined,
    });

    config.value = updated;
    isEditing.value = false;
    toast.add({
      severity: 'success',
      summary: 'Success',
      detail: 'OIDC configuration updated successfully',
      life: 3000,
    });
  } catch (e: any) {
    editError.value = e?.error || e?.message || 'Failed to update configuration';
  } finally {
    editLoading.value = false;
  }
}

function openValidateDialog() {
  secretRefToValidate.value = '';
  validateResult.value = null;
  showValidateDialog.value = true;
}

async function validateSecret() {
  if (!secretRefToValidate.value.trim()) {
    validateResult.value = { valid: false, message: 'Secret reference is required' };
    return;
  }

  validateLoading.value = true;
  validateResult.value = null;

  try {
    validateResult.value = await authConfigsApi.validateSecret(secretRefToValidate.value.trim());
  } catch (e: any) {
    validateResult.value = {
      valid: false,
      message: e?.error || e?.message || 'Validation failed',
    };
  } finally {
    validateLoading.value = false;
  }
}

async function handleValidateSecret(secretRef: string) {
  try {
    const result = await authConfigsApi.validateSecret(secretRef);
    if (result.valid) {
      toast.add({
        severity: 'success',
        summary: 'Valid',
        detail: result.message,
        life: 3000,
      });
    } else {
      toast.add({
        severity: 'error',
        summary: 'Invalid',
        detail: result.message,
        life: 5000,
      });
    }
  } catch (e: any) {
    toast.add({
      severity: 'error',
      summary: 'Validation Failed',
      detail: e?.error || e?.message || 'Could not validate secret reference',
      life: 5000,
    });
  }
}

async function deleteConfig() {
  if (!config.value) return;

  deleteLoading.value = true;

  try {
    await authConfigsApi.delete(config.value.id);
    toast.add({
      severity: 'success',
      summary: 'Success',
      detail: `Auth configuration for "${config.value.emailDomain}" deleted`,
      life: 3000,
    });
    router.push('/authentication/domain-idps');
  } catch (e: any) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: e?.error || e?.message || 'Failed to delete configuration',
      life: 5000,
    });
  } finally {
    deleteLoading.value = false;
    showDeleteDialog.value = false;
  }
}

function formatDate(dateString: string) {
  return new Date(dateString).toLocaleString();
}

function getProviderSeverity(provider: string) {
  return provider === 'OIDC' ? 'info' : 'secondary';
}

function getProviderLabel(provider: string) {
  return provider === 'OIDC' ? 'OIDC (External IDP)' : 'Internal (Password)';
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <div class="breadcrumb">
          <router-link to="/authentication/domain-idps">Domain IDPs</router-link>
          <i class="pi pi-angle-right" />
          <span>{{ config?.emailDomain || 'Loading...' }}</span>
        </div>
        <h1 class="page-title">Auth Configuration</h1>
      </div>
      <div class="header-actions" v-if="config">
        <Button
          label="Validate Secret"
          icon="pi pi-check-circle"
          severity="secondary"
          outlined
          @click="openValidateDialog"
        />
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="showDeleteDialog = true"
        />
      </div>
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="config">
      <!-- Domain Info Card -->
      <div class="fc-card">
        <div class="card-header">
          <h2>Domain Information</h2>
        </div>
        <div class="info-grid">
          <div class="info-item">
            <label>Email Domain</label>
            <code class="domain-code">{{ config.emailDomain }}</code>
          </div>
          <div class="info-item">
            <label>Auth Provider</label>
            <Tag
              :value="getProviderLabel(config.authProvider)"
              :severity="getProviderSeverity(config.authProvider)"
            />
          </div>
          <div class="info-item">
            <label>Created</label>
            <span>{{ formatDate(config.createdAt) }}</span>
          </div>
          <div class="info-item">
            <label>Updated</label>
            <span>{{ formatDate(config.updatedAt) }}</span>
          </div>
        </div>
      </div>

      <!-- OIDC Configuration Card -->
      <div v-if="config.authProvider === 'OIDC'" class="fc-card">
        <div class="card-header">
          <h2>OIDC Configuration</h2>
          <Button
            v-if="!isEditing"
            label="Edit"
            icon="pi pi-pencil"
            text
            @click="startEdit"
          />
        </div>

        <Message v-if="editError" severity="error" :closable="false" class="edit-error">
          {{ editError }}
        </Message>

        <template v-if="isEditing">
          <div class="edit-form">
            <div class="field">
              <label for="edit-issuer">Issuer URL</label>
              <InputText
                id="edit-issuer"
                v-model="editForm.oidcIssuerUrl"
                class="w-full"
              />
            </div>
            <div class="field">
              <label for="edit-client-id">Client ID</label>
              <InputText
                id="edit-client-id"
                v-model="editForm.oidcClientId"
                class="w-full"
              />
            </div>
            <SecretRefInput
              v-model="editForm.oidcClientSecretRef"
              label="Client Secret Reference"
              help-text="Leave empty to keep current secret"
              @validate="handleValidateSecret"
            />

            <div class="field checkbox-field">
              <Checkbox
                id="edit-multi-tenant"
                v-model="editForm.oidcMultiTenant"
                :binary="true"
              />
              <label for="edit-multi-tenant" class="checkbox-label">Multi-Tenant Configuration</label>
            </div>
            <small class="multi-tenant-help">
              Enable for multi-tenant IDPs like Microsoft Entra ID
            </small>

            <div v-if="editForm.oidcMultiTenant" class="field">
              <label for="edit-issuer-pattern">Issuer Pattern</label>
              <InputText
                id="edit-issuer-pattern"
                v-model="editForm.oidcIssuerPattern"
                placeholder="https://login.microsoftonline.com/{tenantId}/v2.0"
                class="w-full"
              />
              <small class="field-help">
                Use {tenantId} as placeholder. Leave empty to auto-derive.
              </small>
            </div>

            <div class="edit-actions">
              <Button label="Cancel" text @click="cancelEdit" :disabled="editLoading" />
              <Button label="Save" icon="pi pi-check" @click="saveEdit" :loading="editLoading" />
            </div>
          </div>
        </template>

        <template v-else>
          <div class="info-grid">
            <div class="info-item full-width">
              <label>Issuer URL</label>
              <span class="oidc-url">{{ config.oidcIssuerUrl }}</span>
            </div>
            <div class="info-item">
              <label>Client ID</label>
              <code>{{ config.oidcClientId }}</code>
            </div>
            <div class="info-item">
              <label>Client Secret</label>
              <span v-if="config.hasClientSecret" class="secret-status configured">
                <i class="pi pi-check-circle" /> Configured
              </span>
              <span v-else class="secret-status not-configured">
                <i class="pi pi-times-circle" /> Not configured
              </span>
            </div>
            <div class="info-item">
              <label>Multi-Tenant</label>
              <span v-if="config.oidcMultiTenant" class="multi-tenant-status enabled">
                <i class="pi pi-check-circle" /> Enabled
              </span>
              <span v-else class="multi-tenant-status disabled">
                <i class="pi pi-times-circle" /> Disabled
              </span>
            </div>
            <div v-if="config.oidcMultiTenant && config.oidcIssuerPattern" class="info-item full-width">
              <label>Issuer Pattern</label>
              <code class="issuer-pattern">{{ config.oidcIssuerPattern }}</code>
            </div>
          </div>
        </template>
      </div>

      <!-- Internal Auth Info -->
      <div v-else class="fc-card">
        <div class="card-header">
          <h2>Internal Authentication</h2>
        </div>
        <p class="internal-info">
          Users from this domain authenticate using internal passwords managed by FlowCatalyst.
          To switch to OIDC authentication, delete this configuration and create a new one with OIDC settings.
        </p>
      </div>
    </template>

    <!-- Validate Secret Dialog -->
    <Dialog
      v-model:visible="showValidateDialog"
      header="Validate Secret Reference"
      modal
      :style="{ width: '500px' }"
    >
      <div class="dialog-content">
        <p class="dialog-description">
          Test if a secret reference is valid and accessible. This checks the secret exists
          without revealing its value.
        </p>

        <div class="field">
          <label for="secret-ref">Secret Reference</label>
          <InputText
            id="secret-ref"
            v-model="secretRefToValidate"
            placeholder="aws-sm://my-secret-name"
            class="w-full"
            @keyup.enter="validateSecret"
          />
          <small class="field-help">
            Supported formats: aws-sm://, aws-ps://, gcp-sm://, vault://
          </small>
        </div>

        <Message
          v-if="validateResult"
          :severity="validateResult.valid ? 'success' : 'error'"
          :closable="false"
        >
          {{ validateResult.message }}
        </Message>
      </div>

      <template #footer>
        <Button label="Close" text @click="showValidateDialog = false" />
        <Button
          label="Validate"
          icon="pi pi-check"
          @click="validateSecret"
          :loading="validateLoading"
        />
      </template>
    </Dialog>

    <!-- Delete Confirmation Dialog -->
    <Dialog
      v-model:visible="showDeleteDialog"
      header="Delete Auth Configuration"
      modal
      :style="{ width: '450px' }"
    >
      <div class="dialog-content">
        <p>
          Are you sure you want to delete the auth configuration for
          <strong>{{ config?.emailDomain }}</strong>?
        </p>

        <Message severity="warn" :closable="false">
          Users from this domain will no longer be able to authenticate until a new
          configuration is created.
        </Message>
      </div>

      <template #footer>
        <Button
          label="Cancel"
          text
          @click="showDeleteDialog = false"
          :disabled="deleteLoading"
        />
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          @click="deleteConfig"
          :loading="deleteLoading"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #64748b;
  margin-bottom: 8px;
}

.breadcrumb a {
  color: var(--primary-color);
  text-decoration: none;
}

.breadcrumb a:hover {
  text-decoration: underline;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.fc-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.card-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-item.full-width {
  grid-column: span 2;
}

.info-item label {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.domain-code {
  background: #f1f5f9;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 14px;
  display: inline-block;
}

.oidc-url {
  color: #0369a1;
  font-size: 14px;
  word-break: break-all;
}

.secret-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.secret-status.configured {
  color: #22c55e;
}

.secret-status.not-configured {
  color: #94a3b8;
}

.multi-tenant-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.multi-tenant-status.enabled {
  color: #22c55e;
}

.multi-tenant-status.disabled {
  color: #94a3b8;
}

.issuer-pattern {
  background: #f1f5f9;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 13px;
  word-break: break-all;
}

.checkbox-field {
  flex-direction: row;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.checkbox-label {
  margin: 0;
  cursor: pointer;
}

.multi-tenant-help {
  color: #64748b;
  font-size: 12px;
  margin-top: -8px;
  margin-bottom: 8px;
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.edit-error {
  margin-bottom: 16px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field label {
  font-weight: 500;
  color: #334155;
}

.field-help {
  color: #64748b;
  font-size: 12px;
}

.internal-info {
  color: #64748b;
  margin: 0;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dialog-description {
  color: #64748b;
  margin: 0;
}

.w-full {
  width: 100%;
}
</style>
