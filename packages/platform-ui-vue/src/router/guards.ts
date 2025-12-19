import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { usePermissionsStore, getRoutePermission } from '@/stores/permissions';
import { usePlatformConfigStore } from '@/stores/platformConfig';
import { checkSession } from '@/api/auth';

/**
 * Guard that ensures user is authenticated.
 * Redirects to login if not authenticated.
 * Also loads platform configuration on first navigation.
 */
export async function authGuard(
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext
): Promise<void> {
  const authStore = useAuthStore();
  const platformConfigStore = usePlatformConfigStore();

  // Load platform config on first navigation (public endpoint, no auth required)
  if (!platformConfigStore.isLoaded) {
    await platformConfigStore.loadConfig();
  }

  // If already authenticated, allow access
  if (authStore.isAuthenticated) {
    next();
    return;
  }

  // If still loading initial session check, wait for it
  if (authStore.isLoading) {
    const isAuthenticated = await checkSession();
    if (isAuthenticated) {
      next();
      return;
    }
  }

  // Not authenticated - redirect to login (replace to avoid back-button loop)
  next({ path: '/auth/login', query: { redirect: to.fullPath }, replace: true });
}

/**
 * Guard that ensures user is NOT authenticated.
 * Used for login page - redirects to dashboard if already logged in.
 */
export async function guestGuard(
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext
): Promise<void> {
  const authStore = useAuthStore();

  // Check session first if still loading
  if (authStore.isLoading) {
    await checkSession();
  }

  // If authenticated, redirect to dashboard (replace to avoid back-button loop)
  if (authStore.isAuthenticated) {
    next({ path: '/dashboard', replace: true });
    return;
  }

  next();
}

/**
 * Guard factory for role-based access.
 */
export function roleGuard(requiredRole: string) {
  return (
    to: RouteLocationNormalized,
    from: RouteLocationNormalized,
    next: NavigationGuardNext
  ): void => {
    const authStore = useAuthStore();
    const roles = authStore.user?.roles || [];

    if (roles.includes(requiredRole)) {
      next();
      return;
    }

    // Redirect to unauthorized or dashboard
    next('/dashboard');
  };
}

/**
 * Guard factory for permission-based access.
 */
export function permissionGuard(requiredPermission: string) {
  return (
    to: RouteLocationNormalized,
    from: RouteLocationNormalized,
    next: NavigationGuardNext
  ): void => {
    const authStore = useAuthStore();
    const permissionsStore = usePermissionsStore();
    const permissions = authStore.user?.permissions || [];

    if (permissions.includes(requiredPermission)) {
      next();
      return;
    }

    // Show permission denied modal
    permissionsStore.showPermissionDenied({
      type: 'route',
      message: 'You do not have permission to access this page.',
      requiredPermission,
      path: to.fullPath,
    });

    // Stay on current page or go to dashboard if no history
    if (from.name) {
      next(false);
    } else {
      next('/dashboard');
    }
  };
}

/**
 * Global navigation guard that checks route permissions.
 * This should be registered as a global beforeEach guard.
 */
export function createRoutePermissionGuard() {
  return (
    to: RouteLocationNormalized,
    from: RouteLocationNormalized,
    next: NavigationGuardNext
  ): void => {
    const authStore = useAuthStore();
    const permissionsStore = usePermissionsStore();

    // Skip for unauthenticated users (authGuard will handle)
    if (!authStore.isAuthenticated) {
      next();
      return;
    }

    // Skip for routes without permission requirements
    const requiredPermission = getRoutePermission(to.path);
    if (!requiredPermission) {
      next();
      return;
    }

    const permissions = authStore.user?.permissions || [];
    const roles = authStore.user?.roles || [];

    // Super admin bypasses all permission checks
    if (roles.includes('platform:super-admin')) {
      next();
      return;
    }

    // Check if user has the required permission
    if (permissions.includes(requiredPermission) || permissions.includes('*')) {
      next();
      return;
    }

    // Show permission denied modal
    permissionsStore.showPermissionDenied({
      type: 'route',
      message: 'You do not have permission to access this page.',
      requiredPermission,
      path: to.fullPath,
    });

    // Stay on current page or go to dashboard if no history
    if (from.name) {
      next(false);
    } else {
      next('/dashboard');
    }
  };
}
