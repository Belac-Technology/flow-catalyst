import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import App from './App.vue'

describe('App.vue', () => {
  const createTestRouter = () => {
    return createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'Dashboard', component: { template: '<div>Dashboard</div>' } },
        { path: '/events', name: 'EventTypes', component: { template: '<div>Events</div>' } },
        { path: '/subscriptions', name: 'Subscriptions', component: { template: '<div>Subscriptions</div>' } },
        { path: '/dispatch', name: 'DispatchJobs', component: { template: '<div>Dispatch</div>' } },
      ],
    })
  }

  it('should render the app title', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    expect(wrapper.text()).toContain('FlowCatalyst')
  })

  it('should render all navigation links', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    expect(wrapper.text()).toContain('Dashboard')
    expect(wrapper.text()).toContain('Event Types')
    expect(wrapper.text()).toContain('Subscriptions')
    expect(wrapper.text()).toContain('Dispatch Jobs')
  })

  it('should have router-view for content', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    const main = wrapper.find('main')
    expect(main.exists()).toBe(true)
  })

  it('should apply active styling to current route', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    const dashboardLink = wrapper.find('a[href="/"]')
    expect(dashboardLink.classes()).toContain('border-primary-500')
    expect(dashboardLink.classes()).toContain('text-gray-900')
  })
})
