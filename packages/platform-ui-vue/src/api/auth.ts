import { useAuthStore, type User } from '@/stores/auth';
import router from '@/router';

const API_URL = '/api';

interface LoginCredentials {
  email: string;
  password: string;
}

interface LoginResponse {
  principalId: number;
  name: string;
  email: string;
  roles: string[];
  tenantId: number | null;
}

export interface DomainCheckResponse {
  authMethod: 'internal' | 'external';
  idpUrl?: string;
}

function mapLoginResponseToUser(response: LoginResponse): User {
  return {
    id: String(response.principalId),
    email: response.email,
    name: response.name,
    tenantId: response.tenantId ? String(response.tenantId) : null,
    roles: response.roles,
    permissions: [],
  };
}

export async function checkEmailDomain(email: string): Promise<DomainCheckResponse> {
  const response = await fetch(`${API_URL}/auth/check-domain`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error('Failed to check email domain');
  }

  return response.json();
}

export async function checkSession(): Promise<boolean> {
  const authStore = useAuthStore();
  authStore.setLoading(true);

  try {
    const response = await fetch(`${API_URL}/auth/me`, {
      credentials: 'include',
    });

    if (!response.ok) {
      authStore.clearAuth();
      return false;
    }

    const data: LoginResponse = await response.json();
    authStore.setUser(mapLoginResponseToUser(data));
    return true;
  } catch {
    authStore.clearAuth();
    return false;
  }
}

export async function login(credentials: LoginCredentials): Promise<void> {
  const authStore = useAuthStore();
  authStore.setLoading(true);
  authStore.setError(null);

  try {
    const response = await fetch(`${API_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
      credentials: 'include',
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.error || 'Login failed. Please check your credentials.');
    }

    const data: LoginResponse = await response.json();
    authStore.setUser(mapLoginResponseToUser(data));
    // Use replace to avoid back-button returning to login
    await router.replace('/dashboard');
  } catch (error: any) {
    authStore.setLoading(false);
    authStore.setError(error.message);
    throw error;
  }
}

export async function logout(): Promise<void> {
  const authStore = useAuthStore();

  try {
    await fetch(`${API_URL}/auth/logout`, {
      method: 'POST',
      credentials: 'include',
    });
  } catch {
    // Ignore errors - clear local state anyway
  }

  authStore.clearAuth();
  // Use replace to clear navigation history on logout
  await router.replace('/auth/login');
}

export async function switchTenant(tenantId: string): Promise<void> {
  const authStore = useAuthStore();

  try {
    const response = await fetch(`${API_URL}/auth/tenant/${tenantId}`, {
      method: 'POST',
      credentials: 'include',
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || 'Failed to switch tenant');
    }

    authStore.selectTenant(tenantId);
  } catch (error: any) {
    authStore.setError(error.message);
    throw error;
  }
}
