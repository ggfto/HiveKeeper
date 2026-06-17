/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// A strict Content-Security-Policy for the PRODUCTION bundle only — it limits what a successful XSS could do
// (the OIDC access token lives in sessionStorage, so script-src is the real guard). Not applied in dev because
// Vite's HMR client uses inline + eval scripts. connect-src allows same-origin (the /gw API proxy) and any
// https origin (a TLS IdP / gateway in prod); tighten it to your exact IdP + gateway origins per deployment.
function securityHeaders() {
  const csp = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    "connect-src 'self' https:",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
    "form-action 'self' https:",
  ].join('; ')
  return {
    name: 'hk-security-headers',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace(
        '</head>',
        `  <meta http-equiv="Content-Security-Policy" content="${csp}" />\n  </head>`,
      )
    },
  }
}

// Same-origin in dev via one proxy:
//   /gw -> hive-gateway (mode C: cloud control plane + on-prem agent). The console is gateway-only.
export default defineConfig({
  // The VITE_DEMO build is a static, mock-backed demo published under a subpath (e.g. /HiveKeeper/demo/);
  // a relative base lets its assets resolve there without hardcoding the path. The normal build stays at root.
  base: process.env.VITE_DEMO ? './' : '/',
  plugins: [react(), securityHeaders()],
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
