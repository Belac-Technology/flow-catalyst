import { apiFetch } from './client';

export interface Client {
  id: string;
  name: string;
  identifier: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  statusReason?: string;
  statusChangedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ClientListResponse {
  clients: Client[];
  total: number;
}

export interface CreateClientRequest {
  name: string;
  identifier: string;
}

export interface UpdateClientRequest {
  name: string;
}

export interface ClientApplication {
  id: string;
  code: string;
  name: string;
  description?: string;
  iconUrl?: string;
  active: boolean;
  enabledForClient: boolean;
}

export interface ClientApplicationsResponse {
  applications: ClientApplication[];
  total: number;
}

export const clientsApi = {
  list(status?: string): Promise<ClientListResponse> {
    const params = status ? `?status=${status}` : '';
    return apiFetch(`/admin/platform/clients${params}`);
  },

  get(id: string): Promise<Client> {
    return apiFetch(`/admin/platform/clients/${id}`);
  },

  getByIdentifier(identifier: string): Promise<Client> {
    return apiFetch(`/admin/platform/clients/by-identifier/${identifier}`);
  },

  create(data: CreateClientRequest): Promise<Client> {
    return apiFetch('/admin/platform/clients', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  update(id: string, data: UpdateClientRequest): Promise<Client> {
    return apiFetch(`/admin/platform/clients/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },

  activate(id: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${id}/activate`, {
      method: 'POST',
    });
  },

  suspend(id: string, reason: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${id}/suspend`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },

  deactivate(id: string, reason: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${id}/deactivate`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },

  addNote(id: string, category: string, text: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${id}/notes`, {
      method: 'POST',
      body: JSON.stringify({ category, text }),
    });
  },

  // Application management
  getApplications(clientId: string): Promise<ClientApplicationsResponse> {
    return apiFetch(`/admin/platform/clients/${clientId}/applications`);
  },

  enableApplication(clientId: string, applicationId: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${clientId}/applications/${applicationId}/enable`, {
      method: 'POST',
    });
  },

  disableApplication(clientId: string, applicationId: string): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${clientId}/applications/${applicationId}/disable`, {
      method: 'POST',
    });
  },

  updateApplications(clientId: string, enabledApplicationIds: string[]): Promise<{ message: string }> {
    return apiFetch(`/admin/platform/clients/${clientId}/applications`, {
      method: 'PUT',
      body: JSON.stringify({ enabledApplicationIds }),
    });
  },
};
