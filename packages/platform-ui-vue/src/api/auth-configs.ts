import { apiFetch } from './client';

export type AuthProvider = 'INTERNAL' | 'OIDC';

export interface AuthConfig {
  id: string;
  emailDomain: string;
  clientId: string | null;
  authProvider: AuthProvider;
  oidcIssuerUrl: string | null;
  oidcClientId: string | null;
  hasClientSecret: boolean;
  oidcMultiTenant: boolean;
  oidcIssuerPattern: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuthConfigListResponse {
  configs: AuthConfig[];
  total: number;
}

export interface CreateInternalConfigRequest {
  emailDomain: string;
  clientId?: number | null;
}

export interface CreateOidcConfigRequest {
  emailDomain: string;
  clientId?: number | null;
  oidcIssuerUrl: string;
  oidcClientId: string;
  oidcClientSecretRef?: string;
  oidcMultiTenant?: boolean;
  oidcIssuerPattern?: string;
}

export interface UpdateOidcConfigRequest {
  oidcIssuerUrl: string;
  oidcClientId: string;
  oidcClientSecretRef?: string;
  oidcMultiTenant?: boolean;
  oidcIssuerPattern?: string;
}

export interface ValidateSecretRequest {
  secretRef: string;
}

export interface SecretValidationResponse {
  valid: boolean;
  message: string;
}

export const authConfigsApi = {
  list(clientId?: number): Promise<AuthConfigListResponse> {
    const params = clientId ? `?clientId=${clientId}` : '';
    return apiFetch(`/admin/platform/auth-configs${params}`);
  },

  get(id: string): Promise<AuthConfig> {
    return apiFetch(`/admin/platform/auth-configs/${id}`);
  },

  getByDomain(domain: string): Promise<AuthConfig> {
    return apiFetch(`/admin/platform/auth-configs/by-domain/${encodeURIComponent(domain)}`);
  },

  createInternal(data: CreateInternalConfigRequest): Promise<AuthConfig> {
    return apiFetch('/admin/platform/auth-configs/internal', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  createOidc(data: CreateOidcConfigRequest): Promise<AuthConfig> {
    return apiFetch('/admin/platform/auth-configs/oidc', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  updateOidc(id: string, data: UpdateOidcConfigRequest): Promise<AuthConfig> {
    return apiFetch(`/admin/platform/auth-configs/${id}/oidc`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },

  delete(id: string): Promise<void> {
    return apiFetch(`/admin/platform/auth-configs/${id}`, {
      method: 'DELETE',
    });
  },

  validateSecret(secretRef: string): Promise<SecretValidationResponse> {
    return apiFetch('/admin/platform/auth-configs/validate-secret', {
      method: 'POST',
      body: JSON.stringify({ secretRef }),
    });
  },
};
