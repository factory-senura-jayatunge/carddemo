import { legacyUpper } from '@carddemo/validation/legacyCase';
import type { FormRules } from '@carddemo/validation/rules';
import { decodeUserKey, encodeUserKey } from '@/lib/userKey';
import type { MessageKey } from '@/messages';
import { form, today, type FieldProblem } from '@/validation/forms';
import { userFormRules } from '@/validation/userRules';
import type { MockUser } from './data';
import { json } from './http';
import { messages } from './messages';

/**
 * identity-service's user administration (USRL, USRA, USRU, USRD) as its contract builds it:
 * ADMIN only, the canonical user ID (a to z upper-cased, nothing else), the key form of the
 * user ID, keyset paging with tokens that carry the search, If-Match versions, the logical
 * delete, the batch window and the check order of each unit.
 */

type Severity = 'error' | 'success' | 'info';
interface Text {
  code: string;
  text: string;
  severity: Severity;
}

function say(key: MessageKey, values: Record<string, string> = {}): Text {
  const { code, text, severity } = messages[key];
  return { code, severity, text: text.replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole) };
}

interface ProblemOptions {
  values?: Record<string, string>;
  extra?: Record<string, unknown>;
  headers?: Record<string, string>;
}

const fail = (status: number, key: MessageKey, field: string | null, options: ProblemOptions = {}) => {
  const t = say(key, options.values);
  return json(status, { type: 'about:blank', status, code: t.code, message: t.text, field, severity: t.severity, ...options.extra }, options.headers);
};

/** A refusal raised inside a check; route() turns it into the response. */
class Refusal extends Error {
  constructor(readonly response: Response) {
    super('refused');
  }
}
const refuse = (status: number, key: MessageKey, field: string | null, options?: ProblemOptions): never => {
  throw new Refusal(fail(status, key, field, options));
};

export interface UserContext {
  request: Request;
  url: URL;
  users: Record<string, MockUser>;
  actor: string;
  role: 'ADMIN' | 'USER';
  faults: string[];
  batchWindowOpen: boolean;
  /** DEF-SGN-011 in legacy mode: passwords of 1 to 8 characters, upper-cased a to z before storing. */
  legacyPasswords: boolean;
  /** Ends the open sessions of a user whose password, type or existence changed. */
  endSessions: (userId: string) => void;
}

const SCREEN_ROWS = 10;
const ID_MAX = 8;

/** A failed rule of the table, with its own code, text and field (or the one the caller names). */
const refuseRule = (failed: FieldProblem, field = failed.field): never => {
  throw new Refusal(json(400, { type: 'about:blank', status: 400, code: failed.code, message: failed.message, field, severity: 'error' }));
};

/** The user ID checks of form userAdd (required, Latin-1, at most 8, no space) on a raw ID. */
function idCheck(raw: string): { value: string; failed: FieldProblem | undefined } {
  const rules = form('userAdd');
  return { value: rules.value('userId', raw), failed: rules.validate({ userId: raw }, today()).problems.find((p) => p.field === 'userId') };
}

function canonical(raw: string, field: string): string {
  const { value, failed } = idCheck(raw);
  if (failed) refuseRule(failed, field);
  return legacyUpper(value);
}

/** A search prefix may be empty or hold a space; its other checks are the user ID's. */
function searchPrefix(raw: string, field: string): string {
  const { value, failed } = idCheck(raw);
  if (failed && failed.code !== 'USER_ID_EMPTY' && failed.code !== 'USER_ID_INVALID') refuseRule(failed, field);
  return legacyUpper(value);
}

/** The fields of an add or update in screen order (form userAdd or userUpdate, or Legacy), stopping at the first failure. */
function checkFields(step: 'add' | 'update', legacy: boolean, values: Record<string, string>) {
  const rules = userFormRules(step, legacy);
  const [failed] = rules.validate(values, today()).problems;
  if (failed) refuseRule(failed);
  return rules;
}

/** The password to store: without its pad, and upper-cased a to z in legacy mode (DEF-SGN-011). */
const storedPassword = (rules: FormRules, raw: string, legacy: boolean) => {
  const value = rules.value('password', raw);
  return legacy ? legacyUpper(value) : value;
};

const version = (user: MockUser) => (user.version ?? 1).toString(16).padStart(16, '0');
const etag = (user: MockUser) => ({ ETag: `"${version(user)}"` });
const view = (userId: string, user: MockUser) => ({
  userId,
  firstName: user.firstName,
  lastName: user.lastName,
  userType: user.type,
  version: version(user),
});

/** RowVersions.requireIfMatch: present, then one quoted version. */
function requireIfMatch(request: Request): string {
  const header = request.headers.get('If-Match');
  if (header === null || header.trim() === '') refuse(428, 'PRECONDITION_REQUIRED', null);
  const tag = /^"([0-9a-f]{16})"$/.exec((header ?? '').trim());
  if (!tag) refuse(400, 'INVALID_IF_MATCH', null);
  return tag?.[1] ?? '';
}

const conflict = (userId: string, user: MockUser) =>
  refuse(409, 'RECORD_CHANGED', null, { extra: { current: view(userId, user) }, headers: etag(user) });

const str = (value: unknown) => (typeof value === 'string' ? value : '');

async function readObject(request: Request): Promise<Record<string, unknown>> {
  try {
    const body: unknown = await request.json();
    if (typeof body === 'object' && body !== null && !Array.isArray(body)) return body as Record<string, unknown>;
  } catch {
    // falls through to the refusal
  }
  return refuse(400, 'INVALID_REQUEST', null);
}

function batchWindow(ctx: UserContext) {
  if (ctx.batchWindowOpen) refuse(503, 'USER_ADMIN_BATCH_WINDOW', null, { headers: { 'Retry-After': '300' } });
}

const active = (ctx: UserContext, userId: string) => {
  const user = ctx.users[userId];
  return user && !user.deleted ? user : undefined;
};

function lockedRead(ctx: UserContext, userId: string, failureField: string): MockUser {
  if (ctx.faults.includes('user-read')) refuse(500, 'USER_LOOKUP_FAILED', failureField);
  const user = active(ctx, userId);
  if (!user) return refuse(404, 'USER_ID_NOT_FOUND', 'userId');
  return user;
}

// A page token: base64url of "<prefix length>:<prefix><user ID>", as the service's PageKey.
const pageToken = (prefix: string, userId: string) => encodeUserKey(`${prefix.length}:${prefix}${userId}`);

function readToken(token: string): { prefix: string; userId: string } {
  const invalid = () => refuse(400, 'INVALID_CURSOR', null);
  const key = decodeUserKey(token);
  if (key === null || !/^[0-9]:/.test(key) || key.length < 3) return invalid();
  const length = Number(key[0]);
  const rest = key.slice(2);
  if (length > ID_MAX || rest.length <= length) return invalid();
  const prefix = rest.slice(0, length);
  const userId = rest.slice(length);
  try {
    if (searchPrefix(prefix, 'start') !== prefix || canonical(userId, 'userId') !== userId || !userId.startsWith(prefix)) invalid();
  } catch {
    invalid();
  }
  return { prefix, userId };
}

function list(ctx: UserContext): Response {
  const params = ctx.url.searchParams;
  const given = (name: string) => (params.get(name) ?? '') !== '';
  if (['start', 'startKey', 'after', 'before'].filter(given).length > 1) refuse(400, 'INVALID_PAGE_REQUEST', null);
  const sizeText = params.get('size') ?? '';
  const size = sizeText.trim() === '' ? SCREEN_ROWS : /^[0-9]{1,9}$/.test(sizeText) ? Number(sizeText) : 0;
  if (size < 1 || size > SCREEN_ROWS) refuse(400, 'INVALID_PAGE_SIZE', null, { values: { max: String(SCREEN_ROWS) } });

  let kind: 'first' | 'after' | 'before' = 'first';
  let prefix = '';
  let key = '';
  if (given('after') || given('before')) {
    kind = given('after') ? 'after' : 'before';
    ({ prefix, userId: key } = readToken(params.get(kind) ?? ''));
  } else if (given('startKey')) {
    const decoded = decodeUserKey(params.get('startKey') ?? '');
    if (decoded === null) refuse(400, 'INVALID_REQUEST', null);
    prefix = searchPrefix(decoded ?? '', 'start');
  } else {
    prefix = searchPrefix(params.get('start') ?? '', 'start');
  }
  if (ctx.faults.includes('user-read')) refuse(500, 'USER_LOOKUP_FAILED', 'start');

  // Latin1_General_100_BIN2 is code-point order; within U+0000 to U+00FF that is UTF-16 order.
  const ids = Object.keys(ctx.users)
    .filter((id) => active(ctx, id) && id.startsWith(prefix))
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  let rows: string[];
  let hasNext: boolean;
  let hasPrevious: boolean;
  if (kind === 'before') {
    const earlier = ids.filter((id) => id < key);
    rows = earlier.slice(-size);
    hasPrevious = earlier.length > size;
    hasNext = true;
  } else {
    const later = kind === 'after' ? ids.filter((id) => id > key) : ids;
    rows = later.slice(0, size);
    hasNext = later.length > size;
    hasPrevious = kind === 'after';
  }
  let note: MessageKey | null = null;
  if (kind === 'first') note = rows.length === 0 ? 'USER_LIST_NONE' : hasNext ? null : 'USER_LIST_BOTTOM';
  if (kind === 'after') note = hasNext ? null : 'USER_LIST_BOTTOM';
  if (kind === 'before') note = hasPrevious ? null : 'USER_LIST_TOP';
  const first = rows[0];
  const last = rows[rows.length - 1];
  return json(200, {
    items: rows.map((id) => {
      const user = ctx.users[id] as MockUser;
      return { userId: id, firstName: user.firstName, lastName: user.lastName, userType: user.type };
    }),
    hasNext,
    hasPrevious,
    firstKey: first === undefined ? null : pageToken(prefix, first),
    lastKey: last === undefined ? null : pageToken(prefix, last),
    message: note ? say(note).text : null,
    severity: note ? 'info' : null,
  });
}

function fetchUser(ctx: UserContext, raw: string): Response {
  const userId = canonical(raw, 'userId');
  if (ctx.faults.includes('user-read')) refuse(500, 'USER_LOOKUP_FAILED', 'userId');
  const user = active(ctx, userId);
  if (!user) return refuse(404, 'USER_ID_NOT_FOUND', 'userId');
  return json(200, view(userId, user), etag(user));
}

async function addUser(ctx: UserContext): Promise<Response> {
  const body = await readObject(ctx.request);
  const values = { firstName: str(body.firstName), lastName: str(body.lastName), userId: str(body.userId), password: str(body.password), userType: str(body.userType) };
  const rules = checkFields('add', ctx.legacyPasswords, values);
  const firstName = rules.value('firstName', values.firstName);
  const lastName = rules.value('lastName', values.lastName);
  const userId = legacyUpper(rules.value('userId', values.userId));
  const password = storedPassword(rules, values.password, ctx.legacyPasswords);
  const type = rules.value('userType', values.userType) as 'A' | 'U';
  batchWindow(ctx);
  if (ctx.faults.includes('user-add')) refuse(500, 'USER_ADD_FAILED', 'firstName');
  const earlier = ctx.users[userId];
  if (earlier && !earlier.deleted) refuse(409, 'USER_ID_EXISTS', 'firstName');
  // A deleted user's row is reused (D-56): its version moves on, so an old If-Match stays stale.
  const user: MockUser = {
    firstName,
    lastName,
    type,
    password,
    mustChange: false,
    failedAttempts: 0,
    lockedUntil: 0,
    version: earlier ? (earlier.version ?? 1) + 1 : 1,
  };
  ctx.users[userId] = user;
  const t = say('USER_ADDED', { userId });
  return json(201, { message: t.text, severity: t.severity, user: view(userId, user) }, {
    Location: `/api/users/key/${encodeUserKey(userId)}`,
    ...etag(user),
  });
}

async function updateUser(ctx: UserContext, raw: string): Promise<Response> {
  const body = await readObject(ctx.request);
  const userId = canonical(raw, 'userId');
  const expected = requireIfMatch(ctx.request);
  const values = { firstName: str(body.firstName), lastName: str(body.lastName), userId, password: str(body.password), userType: str(body.userType) };
  const rules = checkFields('update', ctx.legacyPasswords, values);
  const firstName = rules.value('firstName', values.firstName);
  const lastName = rules.value('lastName', values.lastName);
  const password = rules.value('password', values.password) === '' ? null : storedPassword(rules, values.password, ctx.legacyPasswords);
  const type = rules.value('userType', values.userType) as 'A' | 'U';
  batchWindow(ctx);
  const user = lockedRead(ctx, userId, 'firstName');
  if (expected !== version(user)) conflict(userId, user);
  if (password !== null && password === user.password) refuse(400, 'NEW_PASSWORD_SAME', 'password');
  const typeChanged = type !== user.type;
  const changed =
    firstName !== rules.value('firstName', user.firstName) || lastName !== rules.value('lastName', user.lastName) || password !== null || typeChanged;
  if (!changed) refuse(400, 'USER_NO_CHANGE', 'firstName');
  if (ctx.faults.includes('user-update')) refuse(500, 'USER_UPDATE_FAILED', 'firstName');
  Object.assign(user, { firstName, lastName, type, version: (user.version ?? 1) + 1 });
  if (password !== null) Object.assign(user, { password, mustChange: false, failedAttempts: 0, lockedUntil: 0 });
  if (typeChanged || password !== null) ctx.endSessions(userId);
  const t = say('USER_UPDATED', { userId });
  return json(200, { message: t.text, severity: t.severity, user: view(userId, user) }, etag(user));
}

function deleteUser(ctx: UserContext, raw: string): Response {
  const userId = canonical(raw, 'userId');
  const expected = requireIfMatch(ctx.request);
  if (userId === ctx.actor) refuse(409, 'USER_CANNOT_DELETE_SELF', 'userId');
  batchWindow(ctx);
  const user = lockedRead(ctx, userId, 'userId');
  if (expected !== version(user)) conflict(userId, user);
  const otherAdmins = Object.keys(ctx.users).filter((id) => id !== userId && active(ctx, id)?.type === 'A').length;
  if (user.type === 'A' && otherAdmins === 0) refuse(409, 'USER_LAST_ADMIN', 'userId');
  if (ctx.faults.includes('user-delete')) refuse(500, 'USER_DELETE_FAILED', 'userId');
  Object.assign(user, { deleted: true, version: (user.version ?? 1) + 1 });
  ctx.endSessions(userId);
  const t = say('USER_DELETED', { userId });
  return json(200, { message: t.text, severity: t.severity });
}

/** Answers /api/users and its sub-paths, or null for a path it does not own. */
export async function routeUsers(method: string, path: string, ctx: UserContext): Promise<Response | null> {
  if (path !== '/api/users' && !path.startsWith('/api/users/')) return null;
  try {
    if (ctx.role !== 'ADMIN') return fail(403, 'ACCESS_DENIED', null);
    if (ctx.faults.includes('unexpected')) {
      const correlationId = crypto.randomUUID();
      return fail(500, 'INTERNAL_ERROR', null, { extra: { correlationId }, headers: { 'X-Correlation-Id': correlationId } });
    }
    if (path === '/api/users') {
      if (method === 'GET') return list(ctx);
      if (method === 'POST') return await addUser(ctx);
      return null;
    }
    const keyed = /^\/api\/users\/key\/([^/]+)$/.exec(path);
    const plain = /^\/api\/users\/([^/]+)$/.exec(path);
    let userId: string;
    if (keyed) {
      const decoded = decodeUserKey(keyed[1] ?? '');
      if (decoded === null) return fail(400, 'INVALID_REQUEST', null);
      userId = decoded;
    } else if (plain) {
      try {
        userId = decodeURIComponent(plain[1] ?? '');
      } catch {
        return fail(400, 'INVALID_REQUEST', null);
      }
    } else {
      return null;
    }
    if (method === 'GET') return fetchUser(ctx, userId);
    if (method === 'PUT') return await updateUser(ctx, userId);
    if (method === 'DELETE') return deleteUser(ctx, userId);
    return null;
  } catch (error) {
    if (error instanceof Refusal) return error.response;
    throw error;
  }
}
