import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    // /ws 透传 SSE（/ws/v1/live-stream、/ws/v2/stream），开发模式下 EventSource 才能连上后端
    proxy: { '/api': 'http://localhost:8080', '/ws': 'http://localhost:8080' },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
