import { describe, expect, it } from 'vitest';
import { billPayStep, parseMenuOption } from './rules';

describe('Bill Pay Enter, in COBIL00C order', () => {
  it('@VAL-BILL-001 a blank account ID wins over any confirmation', () => {
    for (const confirm of ['', 'Y', 'N', 'X']) {
      expect(billPayStep('  ', confirm)).toEqual({ kind: 'problem', problem: { field: 'acctId', key: 'ACCT_ID_REQUIRED' } });
    }
  });

  it('@VAL-BILL-002 N or n clears, Y or y pays, blank previews', () => {
    expect(billPayStep('00000000001', 'N')).toEqual({ kind: 'clear' });
    expect(billPayStep('00000000001', 'n')).toEqual({ kind: 'clear' });
    expect(billPayStep('00000000001', 'Y')).toEqual({ kind: 'pay' });
    expect(billPayStep('00000000001', 'y')).toEqual({ kind: 'pay' });
    expect(billPayStep('00000000001', '')).toEqual({ kind: 'preview' });
    expect(billPayStep('00000000001', ' ')).toEqual({ kind: 'preview' });
  });

  it('@VAL-BILL-002 BILL-S8: any other value is refused on the confirm field', () => {
    expect(billPayStep('00000000001', 'X')).toEqual({ kind: 'problem', problem: { field: 'confirm', key: 'INVALID_CONFIRM' } });
  });
});

describe('menu option', () => {
  it('@NUM-MENU-001 @VAL-MENU-001 one or two digits, from 1 to the option count', () => {
    expect(parseMenuOption('10', 11)).toBe(10);
    expect(parseMenuOption(' 5', 11)).toBe(5);
    expect(parseMenuOption('5 ', 11)).toBe(5);
    expect(parseMenuOption('05', 11)).toBe(5);
    for (const typed of ['', '  ', '00', '0', '12', '99', 'A', '1A', '-1']) expect(parseMenuOption(typed, 11)).toBeNull();
    expect(parseMenuOption('5', 4)).toBeNull();
  });
});
