<template>
  <div>
    <h2 class="text-3xl font-bold text-gray-900 mb-6">Dashboard</h2>

    <div class="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
      <Card variant="bordered">
        <template #header>
          <h3 class="text-lg font-semibold">Event Types</h3>
        </template>
        <p class="text-3xl font-bold text-primary-600">{{ stats.eventTypes }}</p>
        <p class="text-sm text-gray-500 mt-2">Registered event types</p>
      </Card>

      <Card variant="bordered">
        <template #header>
          <h3 class="text-lg font-semibold">Active Subscriptions</h3>
        </template>
        <p class="text-3xl font-bold text-primary-600">{{ stats.subscriptions }}</p>
        <p class="text-sm text-gray-500 mt-2">Currently active</p>
      </Card>

      <Card variant="bordered">
        <template #header>
          <h3 class="text-lg font-semibold">Messages Today</h3>
        </template>
        <p class="text-3xl font-bold text-primary-600">{{ stats.messages }}</p>
        <p class="text-sm text-gray-500 mt-2">Processed messages</p>
      </Card>
    </div>

    <div class="mt-8">
      <Card variant="elevated">
        <template #header>
          <h3 class="text-lg font-semibold">Recent Activity</h3>
        </template>
        <p class="text-gray-600">Activity feed coming soon...</p>
      </Card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Card } from '@flowcatalyst/ui-components'

const stats = ref({
  eventTypes: 0,
  subscriptions: 0,
  messages: 0,
})

// TODO: Fetch stats from API
</script>
