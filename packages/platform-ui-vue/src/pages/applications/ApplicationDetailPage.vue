<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useConfirm } from 'primevue/useconfirm';
import { useToast } from 'primevue/usetoast';
import Button from 'primevue/button';
import Tag from 'primevue/tag';
import InputText from 'primevue/inputtext';
import Textarea from 'primevue/textarea';
import ProgressSpinner from 'primevue/progressspinner';
import Message from 'primevue/message';
import { applicationsApi, type Application } from '@/api/applications';

const route = useRoute();
const router = useRouter();
const confirm = useConfirm();
const toast = useToast();

const loading = ref(true);
const application = ref<Application | null>(null);
const editing = ref(false);
const saving = ref(false);

// Edit form
const editName = ref('');
const editDescription = ref('');
const editDefaultBaseUrl = ref('');
const editIconUrl = ref('');

onMounted(async () => {
  const id = route.params.id as string;
  if (id) {
    await loadApplication(id);
  }
});

async function loadApplication(id: string) {
  loading.value = true;
  try {
    application.value = await applicationsApi.get(id);
  } catch {
    application.value = null;
  } finally {
    loading.value = false;
  }
}

function startEditing() {
  if (application.value) {
    editName.value = application.value.name;
    editDescription.value = application.value.description || '';
    editDefaultBaseUrl.value = application.value.defaultBaseUrl || '';
    editIconUrl.value = application.value.iconUrl || '';
    editing.value = true;
  }
}

function cancelEditing() {
  editing.value = false;
}

async function saveChanges() {
  if (!application.value) return;

  saving.value = true;
  try {
    application.value = await applicationsApi.update(application.value.id, {
      name: editName.value,
      description: editDescription.value || undefined,
      defaultBaseUrl: editDefaultBaseUrl.value || undefined,
      iconUrl: editIconUrl.value || undefined,
    });
    editing.value = false;
    toast.add({ severity: 'success', summary: 'Success', detail: 'Application updated', life: 3000 });
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to update', life: 3000 });
  } finally {
    saving.value = false;
  }
}

function confirmActivate() {
  confirm.require({
    message: 'Activate this application?',
    header: 'Activate Application',
    icon: 'pi pi-check-circle',
    acceptLabel: 'Activate',
    accept: activateApplication,
  });
}

async function activateApplication() {
  if (!application.value) return;
  try {
    application.value = await applicationsApi.activate(application.value.id);
    toast.add({ severity: 'success', summary: 'Success', detail: 'Application activated', life: 3000 });
  } catch {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to activate', life: 3000 });
  }
}

function confirmDeactivate() {
  confirm.require({
    message: 'Deactivate this application? It will no longer be available for new event types.',
    header: 'Deactivate Application',
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: 'Deactivate',
    acceptClass: 'p-button-warning',
    accept: deactivateApplication,
  });
}

async function deactivateApplication() {
  if (!application.value) return;
  try {
    application.value = await applicationsApi.deactivate(application.value.id);
    toast.add({ severity: 'success', summary: 'Success', detail: 'Application deactivated', life: 3000 });
  } catch {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to deactivate', life: 3000 });
  }
}

function confirmDelete() {
  confirm.require({
    message: 'Delete this application? This cannot be undone.',
    header: 'Delete Application',
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: 'Delete',
    acceptClass: 'p-button-danger',
    accept: deleteApplication,
  });
}

async function deleteApplication() {
  if (!application.value) return;
  try {
    await applicationsApi.delete(application.value.id);
    toast.add({ severity: 'success', summary: 'Success', detail: 'Application deleted', life: 3000 });
    router.push('/applications');
  } catch {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to delete', life: 3000 });
  }
}

function formatDate(dateString: string) {
  return new Date(dateString).toLocaleString();
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="application">
      <!-- Header -->
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="router.push('/applications')"
            v-tooltip="'Back to list'"
          />
          <div class="header-text">
            <h1 class="page-title">{{ application.name }}</h1>
            <code class="app-code">{{ application.code }}</code>
          </div>
          <Tag
            :value="application.active ? 'Active' : 'Inactive'"
            :severity="application.active ? 'success' : 'secondary'"
          />
        </div>
      </header>

      <!-- Details Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Application Details</h3>
          <Button
            v-if="!editing"
            icon="pi pi-pencil"
            label="Edit"
            text
            @click="startEditing"
          />
        </div>
        <div class="card-content">
          <template v-if="editing">
            <div class="form-field">
              <label>Name</label>
              <InputText v-model="editName" class="full-width" />
            </div>
            <div class="form-field">
              <label>Description</label>
              <Textarea v-model="editDescription" :rows="3" class="full-width" />
            </div>
            <div class="form-field">
              <label>Default Base URL</label>
              <InputText v-model="editDefaultBaseUrl" class="full-width" placeholder="https://example.com" />
            </div>
            <div class="form-field">
              <label>Icon URL</label>
              <InputText v-model="editIconUrl" class="full-width" placeholder="https://example.com/icon.png" />
            </div>
            <div class="form-actions">
              <Button label="Cancel" severity="secondary" outlined @click="cancelEditing" />
              <Button label="Save" :loading="saving" @click="saveChanges" />
            </div>
          </template>

          <template v-else>
            <div class="detail-grid">
              <div class="detail-item">
                <label>Code</label>
                <code>{{ application.code }}</code>
              </div>
              <div class="detail-item">
                <label>Name</label>
                <span>{{ application.name }}</span>
              </div>
              <div class="detail-item full-width">
                <label>Description</label>
                <span>{{ application.description || '—' }}</span>
              </div>
              <div class="detail-item">
                <label>Default Base URL</label>
                <span>{{ application.defaultBaseUrl || '—' }}</span>
              </div>
              <div class="detail-item">
                <label>Icon URL</label>
                <span>{{ application.iconUrl || '—' }}</span>
              </div>
              <div class="detail-item">
                <label>Created</label>
                <span>{{ formatDate(application.createdAt) }}</span>
              </div>
              <div class="detail-item">
                <label>Updated</label>
                <span>{{ formatDate(application.updatedAt) }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Actions Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Actions</h3>
        </div>
        <div class="card-content">
          <div class="action-items">
            <div v-if="!application.active" class="action-item">
              <div class="action-info">
                <strong>Activate Application</strong>
                <p>Make this application available for use.</p>
              </div>
              <Button
                label="Activate"
                severity="success"
                outlined
                @click="confirmActivate"
              />
            </div>

            <div v-else class="action-item">
              <div class="action-info">
                <strong>Deactivate Application</strong>
                <p>Prevent new event types from using this application.</p>
              </div>
              <Button
                label="Deactivate"
                severity="warn"
                outlined
                @click="confirmDeactivate"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Danger Zone -->
      <div class="section-card danger-zone">
        <div class="card-header danger-header">
          <h3>Danger Zone</h3>
        </div>
        <div class="card-content">
          <div class="action-items">
            <div class="action-item">
              <div class="action-info">
                <strong>Delete Application</strong>
                <p>Permanently delete this application. Cannot be undone.</p>
              </div>
              <Button
                label="Delete"
                severity="danger"
                outlined
                :disabled="application.active"
                @click="confirmDelete"
              />
            </div>
          </div>
        </div>
      </div>
    </template>

    <Message v-else severity="error">Application not found</Message>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 900px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.header-content {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.header-text {
  flex: 1;
}

.app-code {
  display: inline-block;
  margin-top: 4px;
  background: #f1f5f9;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 14px;
  color: #475569;
}

.section-card {
  margin-bottom: 24px;
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  overflow: hidden;
}

.card-content {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-item.full-width {
  grid-column: 1 / -1;
}

.detail-item label {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  text-transform: uppercase;
}

.form-field {
  margin-bottom: 20px;
}

.form-field label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}

.full-width {
  width: 100%;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

.action-items {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.action-info strong {
  display: block;
  margin-bottom: 4px;
}

.action-info p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}

.danger-header h3 {
  color: #dc2626;
}

@media (max-width: 640px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
