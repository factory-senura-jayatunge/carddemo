import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { App } from '@/App';
import { createMockServer, memoryStore, type MockSettings } from '@/mocks/server';
import { routes } from '@/router';

export interface AppOptions {
  /** Sign on as this user before the first render (password PASSWORD). */
  signedOnAs?: string;
  settings?: Partial<MockSettings>;
  functionKeys?: boolean;
}

/**
 * Renders the whole app at a path, with the contract mock answering every
 * API call in memory. requests lists what the app sent.
 */
export async function renderApp(path: string, options: AppOptions = {}) {
  const settings: MockSettings = {
    latencyMs: 0,
    batchWindowOpen: false,
    uiSwitches: {},
    authorizationInstalled: false,
    ...options.settings,
  };
  const server = createMockServer(memoryStore(), () => settings);
  const requests: Request[] = [];
  const fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    requests.push(request.clone());
    return server.handle(request);
  });
  vi.stubGlobal('fetch', fetch);
  if (options.functionKeys) localStorage.setItem('carddemo.fkeys', 'on');
  if (options.signedOnAs) {
    await server.handle(
      new Request(`${window.location.origin}/api/auth/sign-on`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: options.signedOnAs, password: 'PASSWORD' }),
      }),
    );
  }
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup();
  render(<App router={router} />);
  const apiCalls = (path: string) => requests.filter((r) => new URL(r.url).pathname.startsWith(path));
  return { router, requests, apiCalls, settings, user };
}
