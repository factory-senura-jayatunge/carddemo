/// <reference types="vitest/config" />
import { defineConfig, searchForWorkspaceRoot, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// Local service ports (docs/modernization/architecture/overview.md, Deployables). An isolated
// stack on other ports sets CARDDEMO_<NAME>_PORT, for example CARDDEMO_ACCOUNT_PORT=28193.
const portOf = (name: string, standard: number): number => {
  const raw = process.env[`CARDDEMO_${name}_PORT`];
  if (raw === undefined || raw === '') return standard;
  const port = Number(raw);
  if (!/^[0-9]+$/.test(raw) || port < 1 || port > 65535) throw new Error(`CARDDEMO_${name}_PORT must be a port number, not "${raw}"`);
  return port;
};
const service = (name: string, standard: number): ProxyOptions => ({
  target: `http://127.0.0.1:${portOf(name, standard)}`,
  changeOrigin: false,
});

const identity = service('IDENTITY', 28081);
const billpay = service('BILLPAY', 28082);
const account = service('ACCOUNT', 28083);
const transaction = service('TRANSACTION', 28084);
const reference = service('REFERENCE', 28085);
const authorization = service('AUTHORIZATION', 28086);

const proxy: Record<string, ProxyOptions> = {
  '/api/auth': identity,
  '/api/users': identity,
  '/api/features': identity,
  '/api/billpay': billpay,
  '/api/accounts': account,
  '/api/cards': account,
  '/api/transactions': transaction,
  '/api/reports': transaction,
  '/api/transaction-types': reference,
  '/api/reference': reference,
  '/api/authorizations': authorization,
};

const validationTs = fileURLToPath(new URL('../platform/validation/src/main/ts', import.meta.url));
const validationData = fileURLToPath(new URL('../platform/validation/src/main/resources/org/carddemo/platform/validation', import.meta.url));

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // The rule table and its TypeScript engine, shared with the services (architecture/web.md).
      '@carddemo/validation': validationTs,
      '@carddemo/validation-data': validationData,
    },
  },
  server: {
    host: '127.0.0.1',
    port: 25173,
    strictPort: true,
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), validationTs, validationData] },
    // Playwright's traces and screenshots; Vite already ignores test-results/.
    watch: { ignored: ['**/e2e-output/**'] },
    // In mock mode the API is answered in the browser (src/mocks), so nothing is proxied.
    proxy: mode === 'mock' ? undefined : proxy,
  },
  // vite preview of a build proxies the same paths to the same services.
  preview: { host: '127.0.0.1', proxy },
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
    include: ['src/**/*.test.{ts,tsx}', 'e2e/**/*.test.ts'],
    css: false,
    restoreMocks: true,
    // Room for a loaded machine (CI, Maven builds alongside): fewer workers, longer limits.
    maxWorkers: 4,
    testTimeout: 15_000,
    hookTimeout: 15_000,
    unstubGlobals: true,
    reporters: ['default', 'junit'],
    outputFile: { junit: './test-results/vitest-junit.xml' },
  },
}));
