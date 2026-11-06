import { describe, expect, it } from 'vitest';
import { formatClockTime, formatCurrency, formatHeaderDate, formatHeaderTime, legacyBalanceEdit } from './format';
import { displayName } from './names';

describe('balance display', () => {
  it('[NUM-BILL-001] uses the edit +9999999999.99 for positive, zero and negative balances', () => {
    expect(legacyBalanceEdit('194.00')).toBe('+0000000194.00');
    expect(legacyBalanceEdit('0.00')).toBe('+0000000000.00');
    expect(legacyBalanceEdit('-25.50')).toBe('-0000000025.50');
    expect(legacyBalanceEdit('-0.00')).toBe('+0000000000.00');
    expect(legacyBalanceEdit('1234567890.12')).toBe('+1234567890.12');
  });

  it('shows currency next to it', () => {
    expect(formatCurrency('194.00')).toBe('$194.00');
    expect(formatCurrency('-25.5')).toBe('-$25.50');
    expect(formatCurrency('1234567890.12')).toBe('$1,234,567,890.12');
  });
});

describe('header date and time', () => {
  const date = new Date(2026, 10, 6, 9, 5, 7);
  it('[NUM-BILL-004] [NUM-SGN-001] [NUM-MENU-002] date mm/dd/yy and time hh:mm:ss', () => {
    expect(formatHeaderDate(date)).toBe('11/06/26');
    expect(formatHeaderTime(date)).toBe('09:05:07');
    expect(formatHeaderDate(new Date(2000, 0, 31, 23, 59, 59))).toBe('01/31/00');
    expect(formatHeaderTime(new Date(2000, 0, 31, 23, 59, 59))).toBe('23:59:59');
    expect(formatClockTime(date)).toBe('09:05');
  });
});

describe('names', () => {
  it('shows stored upper-case names in title case and keeps mixed case', () => {
    expect(displayName('LAWRENCE THOMAS')).toBe('Lawrence Thomas');
    expect(displayName("O'BRIEN-SMITH ")).toBe("O'Brien-Smith");
    expect(displayName('McDonald')).toBe('McDonald');
  });
});
