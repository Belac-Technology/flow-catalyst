import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import EventTypesView from '../views/EventTypesView.vue'
import SubscriptionsView from '../views/SubscriptionsView.vue'
import DispatchJobsView from '../views/DispatchJobsView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: DashboardView,
    },
    {
      path: '/events',
      name: 'events',
      component: EventTypesView,
    },
    {
      path: '/subscriptions',
      name: 'subscriptions',
      component: SubscriptionsView,
    },
    {
      path: '/dispatch',
      name: 'dispatch',
      component: DispatchJobsView,
    },
  ],
})

export default router
