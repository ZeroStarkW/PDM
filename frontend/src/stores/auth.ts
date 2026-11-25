import { defineStore } from 'pinia'
import { api, ensureCsrf } from '@/api/http'
import type { User } from '@/api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null as User | null, ready: false }),
  getters: {
    authenticated: (state) => state.user !== null,
    hasRole: (state) => (role: string) => state.user?.roles.includes(role) ?? false,
  },
  actions: {
    async loadCurrentUser() {
      if (this.ready) return
      try { this.user = await api<User>('/auth/me') } catch { this.user = null }
      this.ready = true
    },
    async login(username: string, password: string) {
      await ensureCsrf()
      this.user = await api<User>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
      await ensureCsrf()
      this.ready = true
    },
    async logout() {
      await api('/auth/logout', { method: 'POST' })
      this.user = null
    },
  },
})
