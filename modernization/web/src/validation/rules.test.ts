import { describe, expect, it } from 'vitest';
import { billPayStep, checkPasswordChange, checkSignOn, parseMenuOption } from './rules';

describe('sign-on checks', () => {
  it('[VAL-SGN-001] a blank user ID comes first', () => {
    expect(checkSignOn('', '')).toEqual({ field: 'userId', code: 'USER_ID_REQUIRED' });
    expect(checkSignOn('   ', 'PASSWORD')).toEqual({ field: 'userId', code: 'USER_ID_REQUIRED' });
  });

  it('[VAL-SGN-002] then a blank password', () => {
    expect(checkSignOn('ADMIN001', ' ')).toEqual({ field: 'password', code: 'PASSWORD_REQUIRED' });
    expect(checkSignOn('ADMIN001', 'PASSWORD')).toBeNull();
  });
});

describe('Bill Pay Enter, in COBIL00C order', () => {
  it('[VAL-BILL-001] a blank account ID wins over any confirmation', () => {
    for (const confirm of ['', 'Y', 'N', 'X']) {
      expect(billPayStep('  ', confirm)).toEqual({ kind: 'problem', problem: { field: 'acctId', code: 'ACCT_ID_REQUIRED' } });
    }
  });

  it('[VAL-BILL-002] N or n clears, Y or y pays, blank previews', () => {
    expect(billPayStep('00000000001', 'N')).toEqual({ kind: 'clear' });
    expect(billPayStep('00000000001', 'n')).toEqual({ kind: 'clear' });
    expect(billPayStep('00000000001', 'Y')).toEqual({ kind: 'pay' });
    expect(billPayStep('00000000001', 'y')).toEqual({ kind: 'pay' });
    expect(billPayStep('00000000001', '')).toEqual({ kind: 'preview' });
    expect(billPayStep('00000000001', ' ')).toEqual({ kind: 'preview' });
  });

  it('[VAL-BILL-002] BILL-S8: any other value is refused on the confirm field', () => {
    expect(billPayStep('00000000001', 'X')).toEqual({ kind: 'problem', problem: { field: 'confirm', code: 'INVALID_CONFIRM' } });
  });
});

describe('menu option', () => {
  it('[NUM-MENU-001] [VAL-MENU-001] one or two digits, from 1 to the option count', () => {
    expect(parseMenuOption('10', 11)).toBe(10);
    expect(parseMenuOption(' 5', 11)).toBe(5);
    expect(parseMenuOption('5 ', 11)).toBe(5);
    expect(parseMenuOption('05', 11)).toBe(5);
    for (const typed of ['', '  ', '00', '0', '12', '99', 'A', '1A', '-1']) expect(parseMenuOption(typed, 11)).toBeNull();
    expect(parseMenuOption('5', 4)).toBeNull();
  });
});

describe('password change (D-SEC-07, fixed mode)', () => {
  const check = (next: string, confirm = next) => checkPasswordChange('USER0001', 'PASSWORD', next, confirm);

  it('checks the new password in the contract order', () => {
    expect(checkPasswordChange('USER0001', '', 'Abcdefgh', 'Abcdefgh')?.code).toBe('PASSWORD_REQUIRED');
    expect(check('')?.code).toBe('NEW_PASSWORD_REQUIRED');
    expect(check('Short1')?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(check('x'.repeat(65))?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(check('PASSWORD')?.code).toBe('NEW_PASSWORD_SAME');
    expect(check('user0001')?.code).toBe('NEW_PASSWORD_IS_USER_ID');
    expect(check('New pass 1', 'New pass 2')).toEqual({ field: 'confirmPassword', code: 'NEW_PASSWORD_MISMATCH' });
    expect(check('New pass 1')).toBeNull();
    expect(check('x'.repeat(64))).toBeNull();
  });

  it('is case-sensitive: the current password in another case is a new password', () => {
    expect(check('password')).toBeNull();
  });
});
