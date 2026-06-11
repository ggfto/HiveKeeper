import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Same-origin in dev via two proxies:
//   /api -> hive-server  (mode B: direct, single-tenant)
//   /gw  -> hive-gateway (mode C: cloud control plane + on-prem agent)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/gw': {
        target: 'http://127.0.0.1:8090',
        rewrite: (path) => path.replace(/^\/gw/, '')
      }
    }
  }
})
