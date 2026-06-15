/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Same-origin in dev via one proxy:
//   /gw -> hive-gateway (mode C: cloud control plane + on-prem agent). The console is gateway-only.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/gw': {
        target: 'http://127.0.0.1:8090',
        rewrite: (path) => path.replace(/^\/gw/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    css: false,
  },
})
