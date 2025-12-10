import {apiFetch} from './client';

export type PrincipalType = 'USER' | 'SERVICE';
export type IdpType = 'INTERNAL' | 'OIDC' | 'SAML';
export type UserScope = 'ANCHOR' | 'PARTNER' | 'CLIENT';

export interface User {
  id: string;
  type: PrincipalType;
  scope: UserScope | null;
  clientId: string | null;
  name: string;
  active: boolean;
  email: string | null;
  idpType: IdpType | null;
  roles: string[];
  isAnchorUser: boolean;
  grantedClientIds: string[];
  createdAt: string;
  updatedAt: string;
}

export interface UserListResponse {
  principals: User[];
  total: number;
}

export interface CreateUserRequest {
  email: string;
  password?: string;  // Optional - only required for INTERNAL auth users
  name: string;
  clientId?: string;
}

export interface ClientAccessGrant {
  id: string;
  clientId: string;
  grantedAt: string;
  expiresAt: string | null;
}

export interface UpdateUserRequest {
  name: string;
}

export interface EmailDomainCheckResponse {
  domain: string;
  authProvider: string;
  isAnchorDomain: boolean;
  hasAuthConfig: boolean;
  emailExists: boolean;
  info: string | null;
  warning: string | null;
}

export interface UserFilters {
  clientId?: string;
  type?: PrincipalType;
  active?: boolean;
}

export const usersApi = {
  list(filters?: UserFilters): Promise<UserListResponse> {
    const params = new URLSearchParams();
    if (filters?.clientId) params.append('clientId', filters.clientId);
    if (filters?.type) params.append('type', filters.type);
    if (filters?.active !== undefined) params.append('active', String(filters.active));

    const query = params.toString();
    return apiFetch(`/admin/platform/principals${query ? `?${query}` : ''}`);
  },

  get(id: string): Promise<User> {
    return apiFetch(`/admin/platform/principals/${id}`);
  },

  create(data: CreateUserRequest): Promise<User> {
    return apiFetch('/admin/platform/principals/users', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  update(id: string, data: UpdateUserRequest): Promise<User> {
    return apiFetch(`/admin/platform/principals/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },

  activate(id: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/principals/${id}/activate`, {
      method: 'POST',
    });
  },

  deactivate(id: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/principals/${id}/deactivate`, {
      method: 'POST',
    });
  },

  resetPassword(id: string, newPassword: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/principals/${id}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({newPassword}),
    });
  },

  // Client access grants
  getClientAccess(id: string): Promise<{ grants: ClientAccessGrant[] }> {
    return apiFetch(`/admin/platform/principals/${id}/client-access`);
  },

  grantClientAccess(id: string, clientId: string): Promise<ClientAccessGrant> {
    return apiFetch(`/admin/platform/principals/${id}/client-access`, {
      method: 'POST',
      body: JSON.stringify({clientId}),
    });
  },

  revokeClientAccess(id: string, clientId: string): Promise<void> {
    return apiFetch(`/admin/platform/principals/${id}/client-access/${clientId}`, {
      method: 'DELETE',
    });
  },

  checkEmailDomain(email: string): Promise<EmailDomainCheckResponse> {
    return apiFetch(`/admin/platform/principals/check-email-domain?email=${encodeURIComponent(email)}`);
  },
};
