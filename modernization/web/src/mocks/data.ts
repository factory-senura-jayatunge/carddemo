/**
 * Demo data for mock mode: the 50 sample accounts of app/data/ASCII/acctdata.txt
 * with their current balances, and the DUSRSECJ seed users with the public
 * demo password. Two extra accounts, 00000000098 (0.00) and 00000000099
 * (-25.50), exist only here, for the "nothing to pay" case (BILL-S6).
 */
const SAMPLE_BALANCES = [
  194, 158, 147, 40, 345, 218, 193, 605, 560, 159, 212, 176, 41, 15, 489, 733, 33, 144, 480, 369, 112, 55, 104, 400, 61,
  46, 284, 68, 339, 2, 31, 30, 410, 253, 166, 110, 7, 612, 843, 43, 375, 302, 610, 263, 186, 396, 32, 226, 100, 492,
];

/** Balances in cents by account ID. */
export function initialAccounts(): Record<string, number> {
  const accounts: Record<string, number> = {};
  SAMPLE_BALANCES.forEach((dollars, index) => {
    accounts[String(index + 1).padStart(11, '0')] = dollars * 100;
  });
  accounts['00000000098'] = 0;
  accounts['00000000099'] = -2550;
  return accounts;
}

export interface MockUser {
  firstName: string;
  lastName: string;
  type: string;
  password: string;
  mustChange: boolean;
  failedAttempts: number;
  lockedUntil: number;
}

const user = (firstName: string, lastName: string, type: string, password = 'PASSWORD', mustChange = false): MockUser => ({
  firstName,
  lastName,
  type,
  password,
  mustChange,
  failedAttempts: 0,
  lockedUntil: 0,
});

export function initialUsers(): Record<string, MockUser> {
  return {
    ADMIN001: user('MARGARET', 'GOLD', 'A'),
    ADMIN002: user('RUSSELL', 'RUSSELL', 'A'),
    USER0001: user('LAWRENCE', 'THOMAS', 'U'),
    USER0002: user('AJITH', 'KUMAR', 'U'),
    USER0003: user('LAURITZ', 'ALME', 'U'),
    // The scenario users of SGN-S11, SGN-S13 and SGN-S14.
    TEST0001: user('TESSA', 'TEST', 'U', 'Secret12'),
    TYPEX001: user('XAVIER', 'TYPE', 'X'),
    CHANGE01: user('CHARLES', 'CHANGE', 'U', 'PASSWORD', true),
  };
}
