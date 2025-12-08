import { createRouter, createWebHistory } from 'vue-router';
import { authGuard, guestGuard } from './guards';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // Auth routes (no layout, guest only)
    {
      path: '/auth',
      children: [
        {
          path: 'login',
          name: 'login',
          component: () => import('@/pages/auth/LoginPage.vue'),
          beforeEnter: guestGuard,
        },
        {
          path: '',
          redirect: '/auth/login',
        },
      ],
    },
    // Protected routes (with layout)
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      beforeEnter: authGuard,
      children: [
        {
          path: '',
          redirect: '/dashboard',
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/pages/DashboardPage.vue'),
        },
        // Applications
        {
          path: 'applications',
          name: 'applications',
          component: () => import('@/pages/applications/ApplicationListPage.vue'),
        },
        {
          path: 'applications/new',
          name: 'application-create',
          component: () => import('@/pages/applications/ApplicationCreatePage.vue'),
        },
        {
          path: 'applications/:id',
          name: 'application-detail',
          component: () => import('@/pages/applications/ApplicationDetailPage.vue'),
        },
        // Clients
        {
          path: 'clients',
          name: 'clients',
          component: () => import('@/pages/clients/ClientListPage.vue'),
        },
        {
          path: 'clients/new',
          name: 'client-create',
          component: () => import('@/pages/clients/ClientCreatePage.vue'),
        },
        {
          path: 'clients/:id',
          name: 'client-detail',
          component: () => import('@/pages/clients/ClientDetailPage.vue'),
        },
        // Users
        {
          path: 'users',
          name: 'users',
          component: () => import('@/pages/users/UserListPage.vue'),
        },
        // Authorization - Roles
        {
          path: 'authorization/roles',
          name: 'roles',
          component: () => import('@/pages/authorization/RoleListPage.vue'),
        },
        {
          path: 'authorization/roles/:roleName',
          name: 'role-detail',
          component: () => import('@/pages/authorization/RoleDetailPage.vue'),
        },
        // Authorization - Permissions
        {
          path: 'authorization/permissions',
          name: 'permissions',
          component: () => import('@/pages/authorization/PermissionListPage.vue'),
        },
        // Legacy redirect
        {
          path: 'roles',
          redirect: '/authorization/roles',
        },
        // Event Types
        {
          path: 'event-types',
          name: 'event-types',
          component: () => import('@/pages/event-types/EventTypeListPage.vue'),
        },
        {
          path: 'event-types/create',
          name: 'event-type-create',
          component: () => import('@/pages/event-types/EventTypeCreatePage.vue'),
        },
        {
          path: 'event-types/:id',
          name: 'event-type-detail',
          component: () => import('@/pages/event-types/EventTypeDetailPage.vue'),
        },
        {
          path: 'event-types/:id/add-schema',
          name: 'event-type-add-schema',
          component: () => import('@/pages/event-types/EventTypeAddSchemaPage.vue'),
        },
        // Subscriptions
        {
          path: 'subscriptions',
          name: 'subscriptions',
          component: () => import('@/pages/subscriptions/SubscriptionListPage.vue'),
        },
        // Dispatch Jobs
        {
          path: 'dispatch-jobs',
          name: 'dispatch-jobs',
          component: () => import('@/pages/dispatch-jobs/DispatchJobListPage.vue'),
        },
        // Profile
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/pages/ProfilePage.vue'),
        },
      ],
    },
    // Catch-all redirect
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard',
    },
  ],
});

export default router;
