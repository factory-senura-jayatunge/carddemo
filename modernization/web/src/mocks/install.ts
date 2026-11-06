import { createMockServer, type MockSettings, type MockStore } from './server';

const STATE_KEY = 'carddemo.mock.state';

const sessionStore: MockStore = {
  load: () => {
    const raw = sessionStorage.getItem(STATE_KEY);
    return raw ? (JSON.parse(raw) as ReturnType<MockStore['load']>) : null;
  },
  save: (state) => sessionStorage.setItem(STATE_KEY, JSON.stringify(state)),
};

/**
 * Settings a tester can change in the browser console or a Playwright init
 * script, for example sessionStorage.setItem('carddemo.mock.batchWindow', 'open').
 */
function settings(): MockSettings {
  const read = (key: string) => sessionStorage.getItem(`carddemo.mock.${key}`);
  let uiSwitches: MockSettings['uiSwitches'] = {};
  try {
    uiSwitches = JSON.parse(read('switches') ?? '{}') as MockSettings['uiSwitches'];
  } catch {
    uiSwitches = {};
  }
  return {
    latencyMs: Number(read('latency') ?? 250),
    batchWindowOpen: read('batchWindow') === 'open',
    uiSwitches,
    authorizationInstalled: read('authorization') === 'installed',
  };
}

/** Answers /api/* in the browser; every other request goes to the network. */
export function installMock(): void {
  const server = createMockServer(sessionStore, settings);
  const network = window.fetch.bind(window);
  // The API calls of this page load, for tests that check a step made no call
  // (the browser's network log never sees these requests).
  const calls: string[] = [];
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const url = new URL(request.url, window.location.origin);
    if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) return network(input, init);
    calls.push(`${request.method} ${url.pathname}`);
    return server.handle(request);
  };
  Object.assign(window, { carddemoMock: { reset: () => server.reset(), calls } });
}
