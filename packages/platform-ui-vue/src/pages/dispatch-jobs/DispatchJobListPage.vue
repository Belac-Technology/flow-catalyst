<script setup lang="ts">
import { ref, onMounted } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';

const dispatchJobs = ref<any[]>([]);
const loading = ref(true);
const searchQuery = ref('');

onMounted(async () => {
  // TODO: Fetch dispatch jobs from API
  loading.value = false;
});

function getSeverity(status: string): string {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING': return 'info';
    case 'IN_PROGRESS': return 'warn';
    case 'FAILED': return 'danger';
    default: return 'secondary';
  }
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
          <InputText v-model="searchQuery" placeholder="Search jobs..." />
        </span>
      </div>

      <DataTable
        :value="dispatchJobs"
        :loading="loading"
        paginator
        :rows="10"
        :rowsPerPageOptions="[10, 25, 50]"
        stripedRows
        emptyMessage="No dispatch jobs found"
      >
        <Column field="id" header="Job ID" sortable />
        <Column field="eventType" header="Event Type" sortable />
        <Column field="endpoint" header="Endpoint" />
        <Column field="status" header="Status" sortable>
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getSeverity(data.status)" />
          </template>
        </Column>
        <Column field="attempts" header="Attempts" sortable />
        <Column field="createdAt" header="Created" sortable />
        <Column header="Actions">
          <template #body>
            <Button icon="pi pi-eye" text rounded />
            <Button icon="pi pi-replay" text rounded v-tooltip="'Retry'" />
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
</style>
