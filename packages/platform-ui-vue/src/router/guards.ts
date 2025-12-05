import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { checkSession } from '@/api/auth';

/**
 * Guard that ensures user is authenticated.
 * Redirects to login if not authenticated.
 */
export async function authGuard(
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext
): Promise<void> {
  const authStore = useAuthStore();

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
    const permissions = authStore.user?.permissions || [];

    if (permissions.includes(requiredPermission)) {
      next();
      return;
    }

    next('/dashboard');
  };
}
