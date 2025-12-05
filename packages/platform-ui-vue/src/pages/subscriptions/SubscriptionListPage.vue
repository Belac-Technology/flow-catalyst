<script setup lang="ts">
import { ref, onMounted } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';

const subscriptions = ref<any[]>([]);
const loading = ref(true);
const searchQuery = ref('');

onMounted(async () => {
  // TODO: Fetch subscriptions from API
  loading.value = false;
});

function getSeverity(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'success';
    case 'PAUSED': return 'warn';
    case 'DISABLED': return 'danger';
    default: return 'secondary';
  }
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Subscriptions</h1>
        <p class="page-subtitle">Manage event subscriptions and webhook routing</p>
      </div>
      <Button label="Create Subscription" icon="pi pi-plus" />
    </header>

    <div class="fc-card">
      <div class="toolbar">
        <span class="p-input-icon-left">
          <i class="pi pi-search" />
          <InputText v-model="searchQuery" placeholder="Search subscriptions..." />
        </span>
      </div>

      <DataTable
        :value="subscriptions"
        :loading="loading"
        paginator
        :rows="10"
        :rowsPerPageOptions="[10, 25, 50]"
        stripedRows
        emptyMessage="No subscriptions found"
      >
        <Column field="name" header="Name" sortable />
        <Column field="eventType" header="Event Type" sortable />
        <Column field="endpoint" header="Endpoint" />
        <Column field="status" header="Status" sortable>
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getSeverity(data.status)" />
          </template>
        </Column>
        <Column header="Actions">
          <template #body>
            <Button icon="pi pi-eye" text rounded />
            <Button icon="pi pi-pencil" text rounded />
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
