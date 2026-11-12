import { legacyUpper } from '@carddemo/validation/legacyCase';
import { legacyBalanceEdit } from '@/lib/format';
import type { MessageKey } from '@/messages';
import { form, today, type FieldProblem } from '@/validation/forms';
import { initialCore, routeAccounts, type CoreData } from './accounts';
import { initialPauth, routeAuthorizations, type PauthData } from './authorizations';
import { initialAccounts, initialUsers, type MockUser } from './data';
import { decimal, json, problem, readBody, text } from './http';
import { initialReports, routeReports, type ReportState } from './reports';
import { initialAdds, routeTransactionAdd, type AddState } from './transactionAdd';
import { routeTransactions } from './transactions';
import { routeUsers } from './users';
import { fillMessage, messages } from './messages';

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
  /**
   * Database steps that fail while listed, as the contract scenarios inject them
   * with DENY: account-read, xref-read, customer-read, card-read, audit,
   * account-lock, customer-lock, card-lock, account-update, card-update; for
   * authorization-service also auth-summary-read, auth-detail-read,
   * auth-next-read and fraud-update; for transaction-service tran-read (and
   * audit for its unmask), xref-read, reference-read and tran-add for its add,
   * and report-trigger, report-job, report-stall, report-not-started and report-file for its
   * reports; and unexpected, an exception no step
   * message covers (the platform's 500 problem).
   */
  faults?: string[];
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
  /** Accounts, customers, cards and the cross-reference; balances stay in accounts above. */
  core?: CoreData;
  /** Pending authorizations: summaries and details. */
  pauth?: PauthData;
  /** Transaction adds waiting for the batch cycle, and their Idempotency-Keys. */
  tranAdds?: AddState;
  /** Transaction report runs. */
  reports?: ReportState;
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

const signOnRules = form('signOn');
const changeRules = form('passwordChange');

/** A 400 problem for a failed rule of the table: its own code, text and field. */
const refused = (failed: FieldProblem) =>
  json(400, { type: 'about:blank', status: 400, code: failed.code, message: failed.message, field: failed.field, severity: 'error' });

export function createMockServer(store: MockStore, settings: () => MockSettings) {
  const fresh = (): MockState => {
    const core = initialCore();
    return {
      accounts: initialAccounts(),
      users: initialUsers(),
      session: null,
      failures: {},
      csrf: random(),
      nextTranId: 1,
      payments: {},
      core,
      pauth: initialPauth(core),
    };
  };

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
    // SignOnService.authenticate on form signOn: only trailing spaces and NULs are padding, so a
    // leading space, a tab or U+3000 stays in the value and is checked as typed.
    const problems = signOnRules.validate({ userId: rawUserId, password }, today()).problems;
    const idProblem = problems.find((p) => p.field === 'userId');
    const passwordProblem = problems.find((p) => p.field === 'password');
    if (idProblem) return refused(idProblem);
    if (passwordProblem?.code === 'PASSWORD_REQUIRED') return refused(passwordProblem);
    const typedId = signOnRules.value('userId', rawUserId);
    // Rule 3 is not in the form: only the service limits the user ID, after both blank checks.
    if ([...typedId].length > 8) return problem(400, 'INVALID_INPUT_USER_ID', 'userId');
    if (passwordProblem) return refused(passwordProblem);
    const userId = legacyUpper(typedId);
    if (sessionUserId !== undefined && userId !== sessionUserId) return problem(400, 'USER_ID_MISMATCH', 'userId');
    const wait = throttled(state);
    if (wait > 0) return problem(429, 'TOO_MANY_ATTEMPTS', 'userId', {}, { 'Retry-After': String(Math.ceil(wait / 1000)) });
    const user = state.users[userId];
    if (!user || user.deleted) return failed(state);
    if (user.lockedUntil > Date.now()) return failed(state);
    if (user.password !== signOnRules.value('password', password)) {
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

  async function route(request: Request, state: MockState): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method.toUpperCase();
    state.failures ??= {};
    if (state.session && Date.now() - state.session.lastSeen > IDLE_MS) state.session = null;
    if (state.session) state.session.lastSeen = Date.now();
    const stored = state.session ? state.users[state.session.userId] : undefined;
    const current = stored && !stored.deleted ? stored : undefined;
    const signedOn = state.session && current ? { userId: state.session.userId, user: current } : null;
    const csrfOk = request.headers.get('X-XSRF-TOKEN') === state.csrf;

    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && !['/api/auth/sign-on', '/api/auth/password'].includes(path) && !csrfOk) {
      return problem(403, 'CSRF_REJECTED', null);
    }

    if (path === '/api/auth/sign-on' && method === 'POST') {
      const body = await readBody(request);
      const result = verify(state, text(body.userId), text(body.password), false);
      return result instanceof Response ? result : startSession(state, ...result);
    }
    if (path === '/api/auth/password' && method === 'POST') {
      const body = await readBody(request);
      const rawUserId = text(body.userId);
      const userId = signedOn && signOnRules.value('userId', rawUserId) === '' ? signedOn.userId : rawUserId;
      const result = verify(state, userId, text(body.currentPassword), true, signedOn?.userId);
      if (result instanceof Response) return result;
      const [id, user] = result;
      const next = text(body.newPassword);
      // Form passwordChange (fixed mode). The current password passed sign-on, so it is the stored one.
      const input = { userId, currentPassword: text(body.currentPassword), newPassword: next };
      const newProblem = changeRules.validate(input, today()).problems.find((p) => p.field === 'newPassword');
      if (newProblem) return refused(newProblem);
      user.password = changeRules.value('newPassword', next);
      user.mustChange = false;
      user.version = (user.version ?? 1) + 1;
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
    if (/^\/api\/(accounts\/[^/]*\/authorizations|authorizations\/)/.test(path)) {
      // Without the module the service does not run, so nothing answers its paths.
      if (!settings().authorizationInstalled) return problem(404, 'NOT_FOUND', null);
      state.core ??= initialCore();
      state.pauth ??= initialPauth(state.core);
      const answer = await routeAuthorizations(method, url, {
        request,
        core: state.core,
        pauth: state.pauth,
        userId: signedOn.userId,
        role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
        faults: settings().faults ?? [],
      });
      return answer ?? problem(404, 'NOT_FOUND', null);
    }
    const users = await routeUsers(method, path, {
      request,
      url,
      users: state.users,
      actor: signedOn.userId,
      role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
      faults: settings().faults ?? [],
      batchWindowOpen: settings().batchWindowOpen,
      legacyPasswords: settings().uiSwitches['DEF-SGN-011'] === 'legacy',
      endSessions: (userId) => {
        if (state.session?.userId === userId) state.session = null;
      },
    });
    if (users) return users;
    if (path.startsWith('/api/accounts/') || path.startsWith('/api/cards/')) {
      state.core ??= initialCore();
      const answer = await routeAccounts(method, path, {
        request,
        balances: state.accounts,
        core: state.core,
        userId: signedOn.userId,
        role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
        faults: settings().faults ?? [],
        batchWindowOpen: settings().batchWindowOpen,
      });
      if (answer) return answer;
    }
    if (path === '/api/transactions' || path.startsWith('/api/transactions/')) {
      state.core ??= initialCore();
      state.tranAdds ??= initialAdds();
      const added = await routeTransactionAdd(method, path, {
        request,
        core: state.core,
        adds: state.tranAdds,
        userId: signedOn.userId,
        role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
        faults: settings().faults ?? [],
        batchWindowOpen: settings().batchWindowOpen,
      });
      if (added) return added;
      const answer = await routeTransactions(method, path, {
        request,
        url,
        core: state.core,
        userId: signedOn.userId,
        role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
        faults: settings().faults ?? [],
      });
      if (answer) return answer;
    }
    if (path === '/api/reports/transactions' || path.startsWith('/api/reports/transactions/')) {
      state.reports ??= initialReports();
      const answer = await routeReports(method, path, {
        request,
        reports: state.reports,
        userId: signedOn.userId,
        role: signedOn.user.type === 'A' ? 'ADMIN' : 'USER',
        faults: settings().faults ?? [],
      });
      if (answer) return answer;
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
