import { describe, expect, it } from 'vitest';
import { userProblem } from './userRules';

describe('user forms', () => {
  const user = { firstName: 'Ada', lastName: 'Lovelace', userId: 'ada00001', password: 'Analytic1', userType: 'U' };

  it('@VAL-USRA-001 add checks the fields in screen order with the table texts', () => {
    expect(userProblem('add', false, user)).toBeNull();
    expect(userProblem('add', false, { ...user, firstName: '\u3000', lastName: '' })).toMatchObject({ code: 'FIRST_NAME_EMPTY', field: 'firstName' });
    expect(userProblem('add', false, { ...user, lastName: 'J\u0100' })).toMatchObject({
      code: 'UNSUPPORTED_CHARACTER',
      message: { text: 'Last Name contains a character that is not supported.' },
    });
    expect(userProblem('add', false, { ...user, lastName: 'A'.repeat(21) })?.message.text).toBe('Last Name must not be longer than 20 characters.');
    expect(userProblem('add', false, { ...user, userId: ' \0' })?.code).toBe('USER_ID_EMPTY');
    expect(userProblem('add', false, { ...user, userId: 'AB CD' })?.code).toBe('USER_ID_INVALID');
    expect(userProblem('add', false, { ...user, userId: '  ada00001  ' })).toBeNull();
    expect(userProblem('add', false, { ...user, password: ' ' })?.code).toBe('PASSWORD_EMPTY');
    expect(userProblem('add', false, { ...user, password: 'Short1' })?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(userProblem('add', false, { ...user, password: 'ADA00001' })?.code).toBe('NEW_PASSWORD_IS_USER_ID');
    expect(userProblem('add', false, { ...user, userType: '' })).toMatchObject({ code: 'USER_TYPE_EMPTY', field: 'userType' });
    expect(userProblem('add', false, { ...user, userType: 'X' })?.code).toBe('USER_TYPE_INVALID');
  });

  it('@DEF-USRU-011 update leaves a blank password alone; legacy mode takes 1 to 8 characters', () => {
    expect(userProblem('update', false, { ...user, password: ' \0' })).toBeNull();
    expect(userProblem('update', false, { ...user, password: 'Short1' })?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(userProblem('update', true, { ...user, password: 'Short1' })).toBeNull();
    expect(userProblem('update', true, { ...user, password: 'Analytic1' })?.message.text).toBe('New Password must be 1 to 8 characters ...');
  });
});
