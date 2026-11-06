/// <reference types="vitest/config" />
import { defineConfig, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// Local service ports (docs/modernization/architecture/overview.md, Deployables).
const service = (port: number): ProxyOptions => ({
  target: `http://127.0.0.1:${port}`,
  changeOrigin: false,
});

const proxy: Record<string, ProxyOptions> = {
  '/api/auth': service(28081),
  '/api/users': service(28081),
  '/api/features': service(28081),
  '/api/billpay': service(28082),
  '/api/accounts': service(28083),
  '/api/cards': service(28083),
  '/api/transactions': service(28084),
  '/api/reports': service(28084),
  '/api/transaction-types': service(28085),
  '/api/reference': service(28085),
  '/api/authorizations': service(28086),
};

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: '127.0.0.1',
    port: 25173,
    strictPort: true,
    // In mock mode the API is answered in the browser (src/mocks), so nothing is proxied.
    proxy: mode === 'mock' ? undefined : proxy,
  },
  build: {
    target: 'es2022',
    manifest: true,
    sourcemap: true,
    chunkSizeWarningLimit: 600,
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
    reporters: ['default', 'junit'],
    outputFile: { junit: './test-results/vitest-junit.xml' },
  },
}));
