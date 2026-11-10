import { legacyBalanceEdit } from '@/lib/format';
import { fillMessage } from '@/lib/messages';
import { messages, type MessageKey } from '@/messages';
import { initialAccounts, initialUsers, type MockUser } from './data';

/**
 * An in-browser stand-in for identity-service and billpay-service that
 * follows the slice contract, in fixed mode: neutral sign-on failures,
 * case-sensitive passwords, a 5-failure lockout, the per-minute limit on
 * failed attempts, a 15-minute idle session, and the Bill Pay rules in order. It runs only in `vite --mode mock` and in tests.
 */
export interface MockSettings {
  latencyMs: number;
  batchWindowOpen: boolean;
  uiSwitches: Record<string, 'legacy' | 'fixed'>;
  authorizationInstalled: boolean;
}

interface MockState {
  accounts: Record<string, number>;
  users: Record<string, MockUser>;
  session: { id: string; userId: string; lastSeen: number } | null;
  /** Times of failed sign-on attempts in the last minute, per user ID and for this one client. */
  failures: Record<string, number[]>;
  csrf: string;
  nextTranId: number;
  payments: Record<string, { acctId: string; userId: string; result: unknown }>;
}

export interface MockStore {
  load: () => MockState | null;
  save: (state: MockState) => void;
}

export const memoryStore = (): MockStore => {
  let saved: MockState | null = null;
  return { load: () => (saved ? structuredClone(saved) : null), save: (state) => (saved = structuredClone(state)) };
};

const random = () => Math.random().toString(36).slice(2, 12);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LOCK_MS = 15 * 60_000;
const IDLE_MS = 15 * 60_000;
const WINDOW_MS = 60_000;
// identity-service's default (carddemo.identity.rate-limit.*); there is no per-user limit.
const FAILURES_PER_CLIENT = 30;

const decimal = (cents: number) => {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
};

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });

/** A problem response with a catalogue message; the API code is the key's code. */
const problem = (status: number, key: MessageKey, field: string | null, extra: Record<string, unknown> = {}, headers?: Record<string, string>) =>
  json(
    status,
    { type: 'about:blank', status, code: messages[key].code, message: fillMessage(key), field, severity: messages[key].severity, ...extra },
    headers,
  );

export function createMockServer(store: MockStore, settings: () => MockSettings) {
  const fresh = (): MockState => ({
    accounts: initialAccounts(),
    users: initialUsers(),
    session: null,
    failures: {},
    csrf: random(),
    nextTranId: 1,
    payments: {},
  });

  const sessionView = (userId: string, user: MockUser) => ({
    userId,
    userType: user.type,
    role: user.type === 'A' ? 'ADMIN' : 'USER',
    firstName: user.firstName,
    lastName: user.lastName,
    home: user.type === 'A' ? '/admin' : '/',
    idleTimeoutSeconds: 900,
  });

  const recent = (state: MockState, key: string) => (state.failures[key] ?? []).filter((t) => t > Date.now() - WINDOW_MS);

  /** Milliseconds until this client is under the failure limit again; 0 when it is. */
  const throttled = (state: MockState) => {
    const times = recent(state, 'client');
    return times.length >= FAILURES_PER_CLIENT ? (times[0] ?? 0) + WINDOW_MS - Date.now() : 0;
  };

  const failed = (state: MockState) => {
    state.failures.client = [...recent(state, 'client'), Date.now()];
    return problem(401, 'SIGN_ON_FAILED', 'password');
  };

  /**
   * Sign-on rules 1 to 9 of the contract; returns the user or an error response. A password
   * change with a session passes the session's user, which the body's user ID must name.
   */
  const verify = (
    state: MockState,
    rawUserId: string,
    password: string,
    allowMustChange: boolean,
    sessionUserId?: string,
  ): [string, MockUser] | Response => {
    if (rawUserId.trim() === '') return problem(400, 'USER_ID_REQUIRED', 'userId');
    if (password.trim() === '') return problem(400, 'PASSWORD_REQUIRED', 'password');
    if (rawUserId.length > 8) return problem(400, 'INVALID_INPUT_USER_ID', 'userId');
    if (password.length > 64) return problem(400, 'INVALID_INPUT_PASSWORD', 'password');
    const userId = rawUserId.trim().toUpperCase();
    if (sessionUserId !== undefined && userId !== sessionUserId) return problem(400, 'USER_ID_MISMATCH', 'userId');
    const wait = throttled(state);
    if (wait > 0) return problem(429, 'TOO_MANY_ATTEMPTS', 'userId', {}, { 'Retry-After': String(Math.ceil(wait / 1000)) });
    const user = state.users[userId];
    if (!user) return failed(state);
    if (user.lockedUntil > Date.now()) return failed(state);
    if (user.password !== password.replace(/ +$/, '')) {
      user.failedAttempts += 1;
      if (user.failedAttempts >= 5) {
        user.lockedUntil = Date.now() + LOCK_MS;
        user.failedAttempts = 0;
      }
      return failed(state);
    }
    if (user.type !== 'A' && user.type !== 'U') return problem(403, 'USER_TYPE_NOT_ALLOWED', 'userId');
    if (user.mustChange && !allowMustChange) return problem(403, 'PASSWORD_CHANGE_REQUIRED', 'newPassword');
    user.failedAttempts = 0;
    return [userId, user];
  };

  const startSession = (state: MockState, userId: string, user: MockUser) => {
    state.session = { id: random(), userId, lastSeen: Date.now() };
    return json(200, sessionView(userId, user));
  };

  const readBody = async (request: Request): Promise<Record<string, unknown>> => {
    try {
      return (await request.json()) as Record<string, unknown>;
    } catch {
      return {};
    }
  };
  const text = (value: unknown) => (typeof value === 'string' ? value : '');

  async function route(request: Request, state: MockState): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method.toUpperCase();
    state.failures ??= {};
    if (state.session && Date.now() - state.session.lastSeen > IDLE_MS) state.session = null;
    if (state.session) state.session.lastSeen = Date.now();
    const current = state.session ? state.users[state.session.userId] : undefined;
    const signedOn = state.session && current ? { userId: state.session.userId, user: current } : null;
    const csrfOk = request.headers.get('X-XSRF-TOKEN') === state.csrf;

    if (method === 'POST' && !['/api/auth/sign-on', '/api/auth/password'].includes(path) && !csrfOk) {
      return problem(403, 'CSRF_REJECTED', null);
    }

    if (path === '/api/auth/sign-on' && method === 'POST') {
      const body = await readBody(request);
      const result = verify(state, text(body.userId), text(body.password), false);
      return result instanceof Response ? result : startSession(state, ...result);
    }
    if (path === '/api/auth/password' && method === 'POST') {
      const body = await readBody(request);
      const userId = text(body.userId) || signedOn?.userId || '';
      const result = verify(state, userId, text(body.currentPassword), true, signedOn?.userId);
      if (result instanceof Response) return result;
      const [id, user] = result;
      const next = text(body.newPassword);
      const field = 'newPassword';
      if (next === '') return problem(400, 'NEW_PASSWORD_REQUIRED', field);
      if ([...next].length < 8 || [...next].length > 64) return problem(400, 'NEW_PASSWORD_LENGTH_FIXED', field);
      if (next === user.password) return problem(400, 'NEW_PASSWORD_SAME', field);
      if (next.toUpperCase() === id) return problem(400, 'NEW_PASSWORD_IS_USER_ID', field);
      user.password = next;
      user.mustChange = false;
      return startSession(state, id, user);
    }
    if (path === '/api/auth/refresh' && method === 'POST') {
      if (!signedOn) return problem(401, 'SESSION_EXPIRED', null);
      return json(200, sessionView(signedOn.userId, signedOn.user));
    }
    if (path === '/api/auth/sign-off' && method === 'POST') {
      state.session = null;
      return json(200, { message: messages.SIGNED_OFF.text, severity: 'info' });
    }
    if (path === '/api/auth/.well-known/jwks.json') return json(200, { keys: [] });

    if (!signedOn) return problem(401, 'UNAUTHENTICATED', null);

    if (path === '/api/auth/me' && method === 'GET') return json(200, sessionView(signedOn.userId, signedOn.user));
    if (path === '/api/features' && method === 'GET') {
      const { uiSwitches, authorizationInstalled } = settings();
      return json(200, {
        modules: { authorization: authorizationInstalled, transactionTypes: false },
        converted: ['BILL'],
        uiSwitches: { 'DEF-BILL-014': 'fixed', ...uiSwitches },
      });
    }

    const bill = /^\/api\/billpay\/accounts\/([^/]*)(\/payments)?$/.exec(path);
    if (bill) {
      if (signedOn.user.type === 'A') return problem(403, 'ACCESS_DENIED', null);
      const acctId = decodeURIComponent(bill[1] ?? '');
      const blank = acctId.trim() === '';
      const cents = acctId.length <= 11 ? state.accounts[acctId] : undefined;
      const balanceFields = (value: number) => ({ currentBalance: decimal(value), currentBalanceDisplay: legacyBalanceEdit(decimal(value)) });

      if (!bill[2] && method === 'GET') {
        if (blank) return problem(400, 'ACCT_ID_REQUIRED', 'acctId');
        if (cents === undefined) return problem(404, 'ACCOUNT_NOT_FOUND', 'acctId');
        const payable = cents > 0;
        const key: MessageKey = payable ? 'CONFIRM_PAYMENT' : 'NOTHING_TO_PAY';
        return json(200, {
          acctId,
          ...balanceFields(cents),
          payable,
          message: messages[key].text,
          severity: messages[key].severity,
          field: payable ? 'confirm' : 'acctId',
        });
      }
      if (bill[2] && method === 'POST') {
        const body = await readBody(request);
        const key = request.headers.get('Idempotency-Key') ?? '';
        if (blank) return problem(400, 'ACCT_ID_REQUIRED', 'acctId');
        if (!['Y', 'y'].includes(text(body.confirm))) return problem(400, 'INVALID_CONFIRM', 'confirm');
        if (!UUID.test(key)) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', null);
        const earlier = state.payments[key];
        if (earlier) {
          if (earlier.acctId === acctId && earlier.userId === signedOn.userId) return json(201, earlier.result, { 'Idempotent-Replayed': 'true' });
          return problem(422, 'IDEMPOTENCY_KEY_REUSED', null);
        }
        if (settings().batchWindowOpen) return problem(503, 'BATCH_WINDOW', 'acctId', {}, { 'Retry-After': '300' });
        if (cents === undefined) return problem(404, 'ACCOUNT_NOT_FOUND', 'acctId');
        if (cents <= 0) return problem(422, 'NOTHING_TO_PAY', 'acctId', balanceFields(cents));
        const amount = cents % 100_000_000_000;
        const tranId = String(state.nextTranId).padStart(16, '0');
        state.nextTranId += 1;
        state.accounts[acctId] = cents - amount;
        const result = {
          tranId,
          amount: decimal(amount),
          acctId,
          message: fillMessage('PAYMENT_SUCCESSFUL', { tranId }),
          severity: 'success',
          field: 'acctId',
        };
        state.payments[key] = { acctId, userId: signedOn.userId, result };
        return json(201, result);
      }
    }
    return problem(404, 'NOT_FOUND', null);
  }

  // Each request loads the whole state, awaits its body and saves the state again; overlapping
  // requests would overwrite each other's changes (a failed attempt, a payment), so they take turns.
  let turn: Promise<unknown> = Promise.resolve();

  return {
    async handle(request: Request): Promise<Response> {
      const { latencyMs } = settings();
      if (latencyMs > 0) await new Promise((resolve) => setTimeout(resolve, latencyMs));
      const answer = turn.then(async () => {
        const state = store.load() ?? fresh();
        const response = await route(request, state);
        store.save(state);
        document.cookie = `XSRF-TOKEN=${state.csrf}; path=/; SameSite=Strict`;
        return response;
      });
      turn = answer.catch(() => undefined);
      return answer;
    },
    reset() {
      store.save(fresh());
    },
  };
}
