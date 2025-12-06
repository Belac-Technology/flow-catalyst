<script setup lang="ts">
import { ref, onMounted } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import InputText from 'primevue/inputtext';
import Tag from 'primevue/tag';
import { getApiAdminPlatformPrincipals } from '@/api/generated';

const users = ref<any[]>([]);
const loading = ref(true);
const searchQuery = ref('');

onMounted(async () => {
  try {
    const response = await getApiAdminPlatformPrincipals({ query: { type: 'USER' } });
    if (response.data?.principals) {
      users.value = response.data.principals;
    }
  } catch (error) {
    console.error('Failed to fetch users:', error);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Users</h1>
        <p class="page-subtitle">Manage platform users and their access</p>
      </div>
      <Button label="Invite User" icon="pi pi-user-plus" />
    </header>

    <div class="fc-card">
      <div class="toolbar">
        <span class="p-input-icon-left">
          <i class="pi pi-search" />
          <InputText v-model="searchQuery" placeholder="Search users..." />
        </span>
      </div>

      <DataTable
        :value="users"
        :loading="loading"
        paginator
        :rows="10"
        :rowsPerPageOptions="[10, 25, 50]"
        stripedRows
        emptyMessage="No users found"
      >
        <Column field="name" header="Name" sortable />
        <Column field="email" header="Email" sortable />
        <Column field="roles" header="Roles">
          <template #body="{ data }">
            <Tag v-for="role in (data.roles || [])" :key="role" :value="role" class="mr-1" />
          </template>
        </Column>
        <Column field="lastLogin" header="Last Login" sortable />
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
.mr-1 {
  margin-right: 4px;
}
</style>
