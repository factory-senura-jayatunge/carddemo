import { legacyBalanceEdit } from '@/lib/format';
import { fillMessage } from '@/lib/messages';
import { messages, type MessageCode } from '@/messages';
import { initialAccounts, initialUsers, type MockUser } from './data';

/**
 * An in-browser stand-in for identity-service and billpay-service that
 * follows the slice contract, in fixed mode: neutral sign-on failures,
 * case-sensitive passwords, a 5-failure lockout, and the Bill Pay rules in
 * order. It runs only in `vite --mode mock` and in tests.
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
  session: { id: string; userId: string } | null;
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

const problem = (status: number, code: MessageCode, field: string | null, extra: Record<string, unknown> = {}, headers?: Record<string, string>) =>
  json(status, { type: 'about:blank', status, code, message: fillMessage(code), field, severity: messages[code].severity, ...extra }, headers);

export function createMockServer(store: MockStore, settings: () => MockSettings) {
  const fresh = (): MockState => ({
    accounts: initialAccounts(),
    users: initialUsers(),
    session: null,
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

  /** Sign-on rules 1 to 9 of the contract; returns the user or an error response. */
  const verify = (state: MockState, rawUserId: string, password: string, allowMustChange: boolean): [string, MockUser] | Response => {
    if (rawUserId.trim() === '') return problem(400, 'USER_ID_REQUIRED', 'userId');
    if (password.trim() === '') return problem(400, 'PASSWORD_REQUIRED', 'password');
    if (rawUserId.length > 8) return problem(400, 'INVALID_INPUT', 'userId');
    if (password.length > 64) return json(400, { code: 'INVALID_INPUT', message: fillMessage('PASSWORD_REQUIRED'), field: 'password', severity: 'error' });
    const userId = rawUserId.trim().toUpperCase();
    const user = state.users[userId];
    if (!user) return problem(401, 'SIGN_ON_FAILED', 'password');
    if (user.lockedUntil > Date.now()) return problem(401, 'SIGN_ON_FAILED', 'password');
    if (user.password !== password.replace(/ +$/, '')) {
      user.failedAttempts += 1;
      if (user.failedAttempts >= 5) {
        user.lockedUntil = Date.now() + LOCK_MS;
        user.failedAttempts = 0;
      }
      return problem(401, 'SIGN_ON_FAILED', 'password');
    }
    if (user.type !== 'A' && user.type !== 'U') return problem(403, 'USER_TYPE_NOT_ALLOWED', 'userId');
    if (user.mustChange && !allowMustChange) return problem(403, 'PASSWORD_CHANGE_REQUIRED', 'newPassword');
    user.failedAttempts = 0;
    return [userId, user];
  };

  const startSession = (state: MockState, userId: string, user: MockUser) => {
    state.session = { id: random(), userId };
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
    const current = state.session ? state.users[state.session.userId] : undefined;
    const signedOn = state.session && current ? { userId: state.session.userId, user: current } : null;
    const csrfOk = request.headers.get('X-XSRF-TOKEN') === state.csrf;

    if (method === 'POST' && !['/api/auth/sign-on', '/api/auth/password'].includes(path) && !csrfOk) {
      return json(403, { code: 'CSRF', message: 'Invalid CSRF token', field: null, severity: 'error' });
    }

    if (path === '/api/auth/sign-on' && method === 'POST') {
      const body = await readBody(request);
      const result = verify(state, text(body.userId), text(body.password), false);
      return result instanceof Response ? result : startSession(state, ...result);
    }
    if (path === '/api/auth/password' && method === 'POST') {
      const body = await readBody(request);
      const userId = text(body.userId) || signedOn?.userId || '';
      const result = verify(state, userId, text(body.currentPassword), true);
      if (result instanceof Response) return result;
      const [id, user] = result;
      const next = text(body.newPassword);
      const field = 'newPassword';
      if (next === '') return problem(400, 'NEW_PASSWORD_REQUIRED', field);
      if ([...next].length < 8 || [...next].length > 64) return problem(400, 'NEW_PASSWORD_LENGTH', field);
      if (next === user.password) return problem(400, 'NEW_PASSWORD_SAME', field);
      if (next.toUpperCase() === id) return problem(400, 'NEW_PASSWORD_IS_USER_ID', field);
      user.password = next;
      user.mustChange = false;
      return startSession(state, id, user);
    }
    if (path === '/api/auth/refresh' && method === 'POST') {
      if (!signedOn) return json(401, { code: 'SESSION_EXPIRED', message: 'Session expired', field: null, severity: 'info' });
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
        const code: MessageCode = payable ? 'CONFIRM_PAYMENT' : 'NOTHING_TO_PAY';
        return json(200, {
          acctId,
          ...balanceFields(cents),
          payable,
          message: messages[code].text,
          severity: messages[code].severity,
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
    return json(404, { code: 'NOT_FOUND', message: 'Not found', field: null, severity: 'error' });
  }

  return {
    async handle(request: Request): Promise<Response> {
      const { latencyMs } = settings();
      if (latencyMs > 0) await new Promise((resolve) => setTimeout(resolve, latencyMs));
      const state = store.load() ?? fresh();
      const response = await route(request, state);
      store.save(state);
      document.cookie = `XSRF-TOKEN=${state.csrf}; path=/; SameSite=Strict`;
      return response;
    },
    reset() {
      store.save(fresh());
    },
  };
}
