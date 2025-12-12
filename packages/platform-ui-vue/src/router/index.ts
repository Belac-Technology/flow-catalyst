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
        {
          path: 'users/new',
          name: 'user-create',
          component: () => import('@/pages/users/UserCreatePage.vue'),
        },
        {
          path: 'users/:id',
          name: 'user-detail',
          component: () => import('@/pages/users/UserDetailPage.vue'),
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
        {
          path: 'authorization/roles/:roleName/edit',
          name: 'role-edit',
          component: () => import('@/pages/authorization/RoleEditPage.vue'),
        },
        // Authorization - Permissions
        {
          path: 'authorization/permissions',
          name: 'permissions',
          component: () => import('@/pages/authorization/PermissionListPage.vue'),
        },
        // Authentication - Domain IDPs
        {
          path: 'authentication/domain-idps',
          name: 'domain-idps',
          component: () => import('@/pages/authentication/AuthConfigListPage.vue'),
        },
        {
          path: 'authentication/domain-idps/:id',
          name: 'domain-idp-detail',
          component: () => import('@/pages/authentication/AuthConfigDetailPage.vue'),
        },
        // Authentication - Anchor Domains
        {
          path: 'authentication/anchor-domains',
          name: 'anchor-domains',
          component: () => import('@/pages/authentication/AnchorDomainListPage.vue'),
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
        // Dispatch Pools
        {
          path: 'dispatch-pools',
          name: 'dispatch-pools',
          component: () => import('@/pages/dispatch-pools/DispatchPoolListPage.vue'),
        },
        {
          path: 'dispatch-pools/new',
          name: 'dispatch-pool-create',
          component: () => import('@/pages/dispatch-pools/DispatchPoolCreatePage.vue'),
        },
        {
          path: 'dispatch-pools/:id',
          name: 'dispatch-pool-detail',
          component: () => import('@/pages/dispatch-pools/DispatchPoolDetailPage.vue'),
        },
        // Dispatch Jobs
        {
          path: 'dispatch-jobs',
          name: 'dispatch-jobs',
          component: () => import('@/pages/dispatch-jobs/DispatchJobListPage.vue'),
        },
        // Platform - Audit Log
        {
          path: 'platform/audit-log',
          name: 'audit-log',
          component: () => import('@/pages/platform/AuditLogListPage.vue'),
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
