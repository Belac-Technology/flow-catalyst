<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';
import ProgressSpinner from 'primevue/progressspinner';
import Message from 'primevue/message';
import { applicationsApi, type Application } from '@/api/applications';

const router = useRouter();
const applications = ref<Application[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const searchQuery = ref('');

const filteredApplications = computed(() => {
  if (!searchQuery.value) return applications.value;
  const query = searchQuery.value.toLowerCase();
  return applications.value.filter(app =>
    app.code.toLowerCase().includes(query) ||
    app.name.toLowerCase().includes(query) ||
    (app.description?.toLowerCase().includes(query) ?? false)
  );
});

onMounted(async () => {
  await loadApplications();
});

async function loadApplications() {
  loading.value = true;
  error.value = null;
  try {
    const response = await applicationsApi.list();
    applications.value = response.applications;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load applications';
  } finally {
    loading.value = false;
  }
}

function formatDate(dateString: string) {
  return new Date(dateString).toLocaleDateString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Applications</h1>
        <p class="page-subtitle">Manage applications in the platform ecosystem</p>
      </div>
      <Button label="Create Application" icon="pi pi-plus" @click="router.push('/applications/new')" />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <span class="p-input-icon-left search-wrapper">
          <i class="pi pi-search" />
          <InputText v-model="searchQuery" placeholder="Search applications..." />
        </span>
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredApplications"
        paginator
        :rows="10"
        :rowsPerPageOptions="[10, 25, 50]"
        stripedRows
        emptyMessage="No applications found"
      >
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="app-code">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column field="description" header="Description">
          <template #body="{ data }">
            <span class="description-text">{{ data.description || '—' }}</span>
          </template>
        </Column>
        <Column field="active" header="Status" sortable>
          <template #body="{ data }">
            <Tag :value="data.active ? 'Active' : 'Inactive'" :severity="data.active ? 'success' : 'secondary'" />
          </template>
        </Column>
        <Column field="createdAt" header="Created" sortable>
          <template #body="{ data }">
            {{ formatDate(data.createdAt) }}
          </template>
        </Column>
        <Column header="Actions" style="width: 120px">
          <template #body="{ data }">
            <Button
              icon="pi pi-eye"
              text
              rounded
              v-tooltip="'View'"
              @click="router.push(`/applications/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="router.push(`/applications/${data.id}`)"
            />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.search-wrapper {
  position: relative;
}

.search-wrapper .pi-search {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: #94a3b8;
}

.search-wrapper input {
  padding-left: 36px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.app-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.description-text {
  color: #64748b;
  font-size: 14px;
}
</style>
