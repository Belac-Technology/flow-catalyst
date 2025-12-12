<script setup lang="ts">
import Toast from 'primevue/toast';
import ConfirmDialog from 'primevue/confirmdialog';
import GlobalToast from '@/components/GlobalToast.vue';
</script>

<template>
  <RouterView />
  <Toast />
  <ConfirmDialog />
  <GlobalToast />
</template>
