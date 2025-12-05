import { apiFetch } from './client';

export interface Application {
  id: string;
  code: string;
  name: string;
  description?: string;
  defaultBaseUrl?: string;
  iconUrl?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ApplicationListResponse {
  items: Application[];
}

export interface CreateApplicationRequest {
  code: string;
  name: string;
  description?: string;
  defaultBaseUrl?: string;
  iconUrl?: string;
}

export interface UpdateApplicationRequest {
  name?: string;
  description?: string;
  defaultBaseUrl?: string;
  iconUrl?: string;
}

export const applicationsApi = {
  list(activeOnly = false): Promise<ApplicationListResponse> {
    const params = activeOnly ? '?activeOnly=true' : '';
    return apiFetch(`/applications${params}`);
  },

  get(id: string): Promise<Application> {
    return apiFetch(`/applications/${id}`);
  },

  getByCode(code: string): Promise<Application> {
    return apiFetch(`/applications/code/${code}`);
  },

  create(data: CreateApplicationRequest): Promise<Application> {
    return apiFetch('/applications', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  update(id: string, data: UpdateApplicationRequest): Promise<Application> {
    return apiFetch(`/applications/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },

  activate(id: string): Promise<Application> {
    return apiFetch(`/applications/${id}/activate`, { method: 'POST' });
  },

  deactivate(id: string): Promise<Application> {
    return apiFetch(`/applications/${id}/deactivate`, { method: 'POST' });
  },

  delete(id: string): Promise<void> {
    return apiFetch(`/applications/${id}`, { method: 'DELETE' });
  },
};
