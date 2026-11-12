import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';

const settings: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };
const EMOJI_33 = '\u{1F600}'.repeat(33);

function mock() {
  const server = createMockServer(memoryStore(), () => settings);
  const post = async (path: string, body: Record<string, string>) => {
    const response = await server.handle(
      new Request(`${window.location.origin}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
    );
    const json = (await response.json()) as { code?: string; field?: string | null; userId?: string };
    return { status: response.status, code: json.code ?? null, field: json.field ?? null, userId: json.userId ?? null };
  };
  return {
    signOn: (userId: string, password: string) => post('/api/auth/sign-on', { userId, password }),
    change: (userId: string, currentPassword: string, newPassword: string) => post('/api/auth/password', { userId, currentPassword, newPassword }),
  };
}

describe('identity mock: sign-on rules 1 to 4 as SignOnService applies them', () => {
  it('@VAL-SGN-001 @VAL-SGN-002 only trailing spaces and NULs are padding: blank is spaces or NULs, nothing else', async () => {
    const { signOn } = mock();
    expect(await signOn('  \0', 'PASSWORD')).toMatchObject({ status: 400, code: 'USER_ID_REQUIRED', field: 'userId' });
    expect(await signOn('USER0001', ' \0 ')).toMatchObject({ status: 400, code: 'PASSWORD_REQUIRED', field: 'password' });
    // A tab, U+3000, NBSP or U+FEFF is a typed character: the password goes on to the check and is wrong.
    for (const typed of ['\t', '\u3000', '\u00a0', '\ufeff']) {
      expect(await signOn('USER0001', typed)).toMatchObject({ status: 401, code: 'SIGN_ON_FAILED' });
    }
  });

  it('raw IDs longer than 8 that are valid after the trailing pad sign on; a leading space or a ninth character is INVALID_INPUT', async () => {
    const { signOn } = mock();
    expect(await signOn('USER0001  ', 'PASSWORD')).toMatchObject({ status: 200, userId: 'USER0001' });
    expect(await signOn('user0001\0\0\0', 'PASSWORD')).toMatchObject({ status: 200, userId: 'USER0001' });
    expect(await signOn(' USER0001', 'PASSWORD')).toMatchObject({ status: 400, code: 'INVALID_INPUT', field: 'userId' });
    expect(await signOn('USER00012', 'PASSWORD')).toMatchObject({ status: 400, code: 'INVALID_INPUT', field: 'userId' });
  });

  it('rule order: a 9-character ID with a blank password is PASSWORD_REQUIRED; the password limit is 64 code points after the pad', async () => {
    const { signOn } = mock();
    expect(await signOn('USER00012', '   ')).toMatchObject({ code: 'PASSWORD_REQUIRED', field: 'password' });
    expect(await signOn('USER0001', `${'x'.repeat(64)}   `)).toMatchObject({ status: 401, code: 'SIGN_ON_FAILED' });
    expect(await signOn('USER0001', 'x'.repeat(65))).toMatchObject({ status: 400, code: 'INVALID_INPUT', field: 'password' });
    expect(await signOn('USER0001', '\u{1F600}'.repeat(64))).toMatchObject({ status: 401, code: 'SIGN_ON_FAILED' });
    expect(await signOn('USER0001', '\u{1F600}'.repeat(65))).toMatchObject({ code: 'INVALID_INPUT', field: 'password' });
  });

  it('user IDs are upper-cased a to z only', async () => {
    const { signOn } = mock();
    expect(await signOn('uSeR0001', 'PASSWORD')).toMatchObject({ status: 200, userId: 'USER0001' });
    // ı would become I under JavaScript's toUpperCase; LegacyCase leaves it, so there is no such user.
    expect(await signOn('USER000\u0131', 'PASSWORD')).toMatchObject({ status: 401, code: 'SIGN_ON_FAILED' });
  });
});

describe('identity mock: the forced password change (D-SEC-07, fixed mode)', () => {
  it('@D-SEC-07 the new password: blank by the pad rule, 8 to 64 printable code points after the pad, not the same, not the user ID', async () => {
    const { change } = mock();
    expect(await change('CHANGE01', 'PASSWORD', '  \0')).toMatchObject({ code: 'NEW_PASSWORD_REQUIRED', field: 'newPassword' });
    expect(await change('CHANGE01', 'PASSWORD', 'short12 ')).toMatchObject({ code: 'NEW_PASSWORD_LENGTH' });
    expect(await change('CHANGE01', 'PASSWORD', 'tab\there12')).toMatchObject({ code: 'NEW_PASSWORD_LENGTH' });
    expect(await change('CHANGE01', 'PASSWORD', 'PASSWORD   ')).toMatchObject({ code: 'NEW_PASSWORD_SAME' });
    expect(await change('CHANGE01', 'PASSWORD', 'change01  ')).toMatchObject({ code: 'NEW_PASSWORD_IS_USER_ID' });
  });

  it('a raw padded ID and 33 emoji change the password; the stored value is the new one without its pad', async () => {
    const { change, signOn } = mock();
    expect(await change('change01  ', 'PASSWORD', `${EMOJI_33}  `)).toMatchObject({ status: 200, userId: 'CHANGE01' });
    expect(await signOn('CHANGE01', EMOJI_33)).toMatchObject({ status: 200 });
  });
});
