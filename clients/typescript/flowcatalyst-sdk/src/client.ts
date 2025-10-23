import type {
  FlowCatalystConfig,
  EventType,
  Subscription,
  DispatchJob,
  ApiResponse
} from './types'

/**
 * FlowCatalyst client for interacting with the platform
 */
export class FlowCatalystClient {
  private config: FlowCatalystConfig

  constructor(config: FlowCatalystConfig) {
    this.config = {
      timeout: 30000,
      ...config,
    }
  }

  /**
   * Event Types API
   */
  async getEventTypes(): Promise<ApiResponse<EventType[]>> {
    return this.request<EventType[]>('/api/event-types')
  }

  async getEventType(id: string): Promise<ApiResponse<EventType>> {
    return this.request<EventType>(`/api/event-types/${id}`)
  }

  async createEventType(eventType: Omit<EventType, 'id' | 'createdAt'>): Promise<ApiResponse<EventType>> {
    return this.request<EventType>('/api/event-types', {
      method: 'POST',
      body: JSON.stringify(eventType),
    })
  }

  /**
   * Subscriptions API
   */
  async getSubscriptions(): Promise<ApiResponse<Subscription[]>> {
    return this.request<Subscription[]>('/api/subscriptions')
  }

  async getSubscription(id: string): Promise<ApiResponse<Subscription>> {
    return this.request<Subscription>(`/api/subscriptions/${id}`)
  }

  async createSubscription(subscription: Omit<Subscription, 'id' | 'createdAt'>): Promise<ApiResponse<Subscription>> {
    return this.request<Subscription>('/api/subscriptions', {
      method: 'POST',
      body: JSON.stringify(subscription),
    })
  }

  /**
   * Dispatch Jobs API
   */
  async getDispatchJobs(): Promise<ApiResponse<DispatchJob[]>> {
    return this.request<DispatchJob[]>('/api/dispatch-jobs')
  }

  async getDispatchJob(id: string): Promise<ApiResponse<DispatchJob>> {
    return this.request<DispatchJob>(`/api/dispatch-jobs/${id}`)
  }

  /**
   * Internal request method
   */
  private async request<T>(
    path: string,
    options: RequestInit = {}
  ): Promise<ApiResponse<T>> {
    const url = `${this.config.baseUrl}${path}`

    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...(this.config.apiKey && { 'Authorization': `Bearer ${this.config.apiKey}` }),
      ...options.headers,
    }

    try {
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), this.config.timeout)

      const response = await fetch(url, {
        ...options,
        headers,
        signal: controller.signal,
      })

      clearTimeout(timeout)

      if (!response.ok) {
        return {
          error: `HTTP ${response.status}: ${response.statusText}`,
        }
      }

      const data = await response.json()
      return { data }
    } catch (error) {
      return {
        error: error instanceof Error ? error.message : 'Unknown error',
      }
    }
  }
}
