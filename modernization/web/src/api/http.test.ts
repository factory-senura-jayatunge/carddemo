import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, onSessionExpired, parseRetryAfter, unwrap } from './http';

const origin = window.location.origin;
const req = (path: string, init?: RequestInit) => new Request(`${origin}${path}`, init);
const status = (code: number) => new Response('{}', { status: code, headers: { 'Content-Type': 'application/json' } });

afterEach(() => onSessionExpired(undefined));

describe('apiFetch', () => {
  it('sends the XSRF-TOKEN cookie as X-XSRF-TOKEN on unsafe methods only', async () => {
    document.cookie = 'XSRF-TOKEN=abc123; path=/';
    const seen: Request[] = [];
    vi.stubGlobal('fetch', (r: Request) => (seen.push(r), Promise.resolve(status(200))));
    await apiFetch(req('/api/billpay/accounts/1/payments', { method: 'POST' }));
    await apiFetch(req('/api/billpay/accounts/1'));
    expect(seen[0]?.headers.get('X-XSRF-TOKEN')).toBe('abc123');
    expect(seen[1]?.headers.get('X-XSRF-TOKEN')).toBeNull();
  });

  it('[FLOW-BILL-001] on 401 refreshes once and retries the call', async () => {
    const paths: string[] = [];
    let calls = 0;
    vi.stubGlobal('fetch', (r: Request) => {
      paths.push(`${r.method} ${new URL(r.url).pathname}`);
      calls += 1;
      return Promise.resolve(status(calls === 1 ? 401 : 200));
    });
    const response = await apiFetch(req('/api/billpay/accounts/00000000001'));
    expect(response.status).toBe(200);
    expect(paths).toEqual(['GET /api/billpay/accounts/00000000001', 'POST /api/auth/refresh', 'GET /api/billpay/accounts/00000000001']);
  });

  it('[FLOW-BILL-001] reports an ended session when the refresh fails', async () => {
    const expired = vi.fn();
    onSessionExpired(expired);
    vi.stubGlobal('fetch', () => Promise.resolve(status(401)));
    const response = await apiFetch(req('/api/billpay/accounts/00000000001'));
    expect(response.status).toBe(401);
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('does not report a 401 from /api/auth/me, the normal answer when nobody is signed on', async () => {
    const expired = vi.fn();
    onSessionExpired(expired);
    vi.stubGlobal('fetch', () => Promise.resolve(status(401)));
    await apiFetch(req('/api/auth/me'));
    expect(expired).not.toHaveBeenCalled();
  });

  it('never refreshes for sign-on', async () => {
    const fetch = vi.fn(() => Promise.resolve(status(401)));
    vi.stubGlobal('fetch', fetch);
    await apiFetch(req('/api/auth/sign-on', { method: 'POST' }));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

describe('errors', () => {
  it('turns a problem body into an ApiError with the catalogue fields and Retry-After', async () => {
    const body = { code: 'BATCH_WINDOW', message: 'Bill payment is not available...', field: 'acctId', severity: 'error' };
    const response = new Response(JSON.stringify(body), { status: 503, headers: { 'Retry-After': '300' } });
    const error = await unwrap(Promise.resolve({ error: body, response })).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 503, code: 'BATCH_WINDOW', field: 'acctId', severity: 'error', retryAfterSeconds: 300 });
  });

  it('turns a failed fetch into a network error', async () => {
    const error = await unwrap(Promise.reject(new TypeError('Failed to fetch'))).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 0, code: 'NETWORK', isNetwork: true });
  });

  it('reads Retry-After as seconds or as a date', () => {
    expect(parseRetryAfter('120')).toBe(120);
    expect(parseRetryAfter(new Date(10_000 + 90_000).toUTCString(), 10_000)).toBe(90);
    expect(parseRetryAfter(null)).toBeUndefined();
    expect(parseRetryAfter('soon')).toBeUndefined();
  });
});
