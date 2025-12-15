<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';
import Select from 'primevue/select';
import { getApiDispatchJobs } from '@/api/generated';

interface DispatchJob {
  id: string;
  source: string;
  type: string;
  targetUrl: string;
  status: string;
  mode: string;
  clientId?: string;
  subscriptionId?: string;
  dispatchPoolId?: string;
  attemptCount: number;
  maxRetries: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
  lastError?: string;
}

const dispatchJobs = ref<DispatchJob[]>([]);
const loading = ref(true);
const searchQuery = ref('');
const statusFilter = ref<string | null>(null);
const totalRecords = ref(0);
const currentPage = ref(0);
const pageSize = ref(20);

const statusOptions = [
  { label: 'All Statuses', value: null },
  { label: 'Pending', value: 'PENDING' },
  { label: 'Queued', value: 'QUEUED' },
  { label: 'In Progress', value: 'IN_PROGRESS' },
  { label: 'Completed', value: 'COMPLETED' },
  { label: 'Error', value: 'ERROR' },
  { label: 'Cancelled', value: 'CANCELLED' }
];

onMounted(async () => {
  await loadDispatchJobs();
});

async function loadDispatchJobs() {
  loading.value = true;
  try {
    const response = await getApiDispatchJobs({
      query: {
        page: currentPage.value,
        size: pageSize.value,
        status: statusFilter.value || undefined,
        source: searchQuery.value || undefined
      }
    });
    if (response.data) {
      dispatchJobs.value = (response.data.items || []) as DispatchJob[];
      totalRecords.value = response.data.totalItems || 0;
    }
  } catch (error) {
    console.error('Failed to load dispatch jobs:', error);
  } finally {
    loading.value = false;
  }
}

async function onPage(event: { page: number; rows: number }) {
  currentPage.value = event.page;
  pageSize.value = event.rows;
  await loadDispatchJobs();
}

async function onFilterChange() {
  currentPage.value = 0;
  await loadDispatchJobs();
}

function getSeverity(status: string): "success" | "info" | "warn" | "danger" | "secondary" | "contrast" | undefined {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING': return 'info';
    case 'QUEUED': return 'info';
    case 'IN_PROGRESS': return 'warn';
    case 'ERROR': return 'danger';
    case 'CANCELLED': return 'secondary';
    default: return 'secondary';
  }
}

function getModeSeverity(mode: string): "success" | "info" | "warn" | "danger" | "secondary" | "contrast" | undefined {
  switch (mode) {
    case 'IMMEDIATE': return 'success';
    case 'NEXT_ON_ERROR': return 'warn';
    case 'BLOCK_ON_ERROR': return 'danger';
    default: return 'secondary';
  }
}

function formatDate(dateStr: string | undefined): string {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleString();
}

function formatAttempts(job: DispatchJob): string {
  return `${job.attemptCount || 0}/${job.maxRetries || 3}`;
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Dispatch Jobs</h1>
        <p class="page-subtitle">Monitor webhook dispatch jobs and delivery status</p>
      </div>
    </header>

    <div class="fc-card">
      <div class="toolbar">
        <span class="p-input-icon-left">
          <i class="pi pi-search" />
          <InputText
            v-model="searchQuery"
            placeholder="Search by source..."
            @keyup.enter="onFilterChange"
          />
        </span>
        <Select
          v-model="statusFilter"
          :options="statusOptions"
          optionLabel="label"
          optionValue="value"
          placeholder="Filter by status"
          class="ml-2"
          @change="onFilterChange"
        />
        <Button
          icon="pi pi-refresh"
          text
          rounded
          @click="loadDispatchJobs"
          v-tooltip="'Refresh'"
          class="ml-2"
        />
      </div>

      <DataTable
        :value="dispatchJobs"
        :loading="loading"
        :lazy="true"
        :paginator="true"
        :rows="pageSize"
        :totalRecords="totalRecords"
        :rowsPerPageOptions="[10, 20, 50]"
        @page="onPage"
        stripedRows
        emptyMessage="No dispatch jobs found"
        tableStyle="min-width: 60rem"
      >
        <Column field="id" header="Job ID" style="width: 10rem">
          <template #body="{ data }">
            <span class="font-mono text-sm">{{ data.id?.slice(0, 8) }}...</span>
          </template>
        </Column>
        <Column field="type" header="Type" sortable />
        <Column field="source" header="Source" sortable />
        <Column field="status" header="Status" sortable style="width: 8rem">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getSeverity(data.status)" />
          </template>
        </Column>
        <Column field="mode" header="Mode" style="width: 8rem">
          <template #body="{ data }">
            <Tag :value="data.mode || 'IMMEDIATE'" :severity="getModeSeverity(data.mode)" />
          </template>
        </Column>
        <Column header="Attempts" style="width: 6rem">
          <template #body="{ data }">
            {{ formatAttempts(data) }}
          </template>
        </Column>
        <Column field="targetUrl" header="Target URL">
          <template #body="{ data }">
            <span class="text-sm truncate" style="max-width: 200px; display: inline-block;">
              {{ data.targetUrl }}
            </span>
          </template>
        </Column>
        <Column field="createdAt" header="Created" sortable style="width: 10rem">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDate(data.createdAt) }}</span>
          </template>
        </Column>
        <Column header="Actions" style="width: 6rem">
          <template #body="{ data }">
            <Button icon="pi pi-eye" text rounded v-tooltip="'View details'" />
            <Button
              icon="pi pi-replay"
              text
              rounded
              v-tooltip="'Retry'"
              :disabled="data.status === 'COMPLETED' || data.status === 'IN_PROGRESS'"
            />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 16px;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.text-sm {
  font-size: 0.875rem;
}

.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ml-2 {
  margin-left: 0.5rem;
}
</style>
