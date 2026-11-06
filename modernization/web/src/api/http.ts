/**
 * The one fetch every API call goes through. The tokens are HttpOnly cookies
 * the app never sees; this layer adds the CSRF header and turns a 401 into
 * one refresh and one retry.
 */

export type Severity = 'error' | 'success' | 'info';

/** RFC 9457 problem details with the CardDemo fields (code, message, field, severity). */
export interface Problem {
  code: string;
  message: string;
  field?: string | null;
  severity?: Severity;
  [extra: string]: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly field: string | null;
  readonly severity: Severity;
  readonly body: Problem;
  /** Seconds from the Retry-After header, when the server sent one. */
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, body: Problem, retryAfterSeconds?: number) {
    super(body.message);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code;
    this.field = body.field ?? null;
    this.severity = body.severity ?? 'error';
    this.body = body;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  get isNetwork(): boolean {
    return this.status === 0;
  }
}

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
// Calls that never trigger a refresh: they create the session, are the
// refresh itself, or end the session anyway.
const NO_REFRESH = new Set(['/api/auth/sign-on', '/api/auth/refresh', '/api/auth/password', '/api/auth/sign-off']);
// A 401 here is the normal answer when nobody is signed on.
const SILENT_401 = new Set(['/api/auth/me']);

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const item = part.trim();
    if (item.startsWith(prefix)) return decodeURIComponent(item.slice(prefix.length));
  }
  return undefined;
}

type Listener = () => void;
let sessionExpiredListener: Listener | undefined;

/** Called when a call fails with 401 and the refresh fails too. */
export function onSessionExpired(listener: Listener | undefined): void {
  sessionExpiredListener = listener;
}

function withCsrf(request: Request): Request {
  if (UNSAFE_METHODS.has(request.method.toUpperCase())) {
    const token = readCookie(CSRF_COOKIE);
    if (token) request.headers.set(CSRF_HEADER, token);
  }
  return request;
}

let refreshInFlight: Promise<boolean> | undefined;

/** Rotates the access token. Concurrent callers share one refresh. */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= globalThis
    .fetch(withCsrf(new Request(new URL('/api/auth/refresh', window.location.origin), { method: 'POST', credentials: 'same-origin' })))
    .then((response) => response.ok)
    .catch(() => false)
    .finally(() => {
      refreshInFlight = undefined;
    });
  return refreshInFlight;
}

export async function apiFetch(input: Request): Promise<Response> {
  const path = new URL(input.url).pathname;
  const retry = input.clone();
  const response = await globalThis.fetch(withCsrf(input));
  if (response.status !== 401 || NO_REFRESH.has(path)) return response;
  if (!(await refreshSession())) {
    if (!SILENT_401.has(path)) sessionExpiredListener?.();
    return response;
  }
  const second = await globalThis.fetch(withCsrf(retry));
  if (second.status === 401 && !SILENT_401.has(path)) sessionExpiredListener?.();
  return second;
}

export function parseRetryAfter(value: string | null, now = Date.now()): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  if (value.trim() !== '' && Number.isFinite(seconds)) return Math.max(0, Math.round(seconds));
  const date = Date.parse(value);
  return Number.isNaN(date) ? undefined : Math.max(0, Math.round((date - now) / 1000));
}

const NETWORK_MESSAGE = 'The service did not answer. Check your connection and try again.';

function toApiError(response: Response, body: unknown): ApiError {
  const problem = (typeof body === 'object' && body !== null ? body : {}) as Partial<Problem>;
  return new ApiError(
    response.status,
    {
      ...problem,
      code: typeof problem.code === 'string' ? problem.code : `HTTP_${response.status}`,
      message: typeof problem.message === 'string' ? problem.message : NETWORK_MESSAGE,
    },
    parseRetryAfter(response.headers.get('Retry-After')),
  );
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Returns the data of an openapi-fetch result, or throws its ApiError. */
export async function unwrap<T>(call: Promise<FetchResult<T>>): Promise<T> {
  let result: FetchResult<T>;
  try {
    result = await call;
  } catch {
    throw new ApiError(0, { code: 'NETWORK', message: NETWORK_MESSAGE, severity: 'error' });
  }
  if (!result.response.ok) throw toApiError(result.response, result.error);
  return result.data as T;
}
