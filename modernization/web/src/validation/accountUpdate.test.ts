import { describe, expect, it } from 'vitest';
import { detectChanges, UNITS, type AcupField, type AcupValues, type Stored } from './accountUpdate';

// Account 00000000001 and customer 000000001 as stored (contract §6 scenario S4); the clear SSN is the service's.
const STORED: Stored = {
  activeStatus: 'Y',
  openYear: '2014',
  openMonth: '11',
  openDay: '20',
  creditLimit: '2020.00',
  expiryYear: '2025',
  expiryMonth: '05',
  expiryDay: '20',
  cashCreditLimit: '1020.00',
  reissueYear: '2025',
  reissueMonth: '05',
  reissueDay: '20',
  groupId: 'A000000000',
  ssn: '020973888',
  ssn1: '020',
  ssn2: '97',
  ssn3: '3888',
  dobYear: '1961',
  dobMonth: '06',
  dobDay: '08',
  ficoScore: '274',
  firstName: 'Immanuel',
  middleName: '',
  lastName: 'Kessler',
  addrLine1: '618 Deshaun Route',
  stateCd: 'NC',
  addrLine2: 'Apt. 802',
  zip: '12546',
  zip5: '12546',
  zipPlus4: '',
  city: 'Altenwerthshire',
  phone1: '(908)119-8310',
  phone1Area: '908',
  phone1Prefix: '119',
  phone1Line: '8310',
  govtIssuedId: '00000000000049368437',
  phone2: '',
  phone2Area: '',
  phone2Prefix: '',
  phone2Line: '',
  eftAccountId: '0053581756',
  priCardHolderInd: 'Y',
};

const unchanged = Object.fromEntries(Object.entries(STORED).filter(([k]) => !['ssn', 'zip', 'phone1', 'phone2'].includes(k))) as Partial<AcupValues>;
const detect = (edits: Partial<AcupValues>) => detectChanges({ ...unchanged, ...edits }, STORED);
const changedOf = (edits: Partial<AcupValues>) => detect(edits).changedFields;

describe('ACUP change detection (BR-ACUP-102 as fixed by D-50, D-75)', () => {
  it('ACUP-S6: the stored values give no change', () => {
    expect(detect({}).units).toEqual([]);
  });

  it('@BR-ACUP-105 an asterisk and spaces, or only spaces, are empty', () => {
    expect(changedOf({ middleName: '*   ' })).toEqual([]);
    expect(changedOf({ middleName: '   ' })).toEqual([]);
    expect(changedOf({ groupId: '  A000000000 ' })).toEqual([]);
  });

  it('@BR-ACUP-105 a value is stripped before the empty test, and sent as the rules see it', () => {
    expect(changedOf({ middleName: ' *' })).toEqual([]);
    expect(changedOf({ middleName: '  *  ' })).toEqual([]);
    expect(detect({ groupId: ' *', expiryMonth: ' 06' }).changes).toEqual({ groupId: '', expiryYear: '2025', expiryMonth: '06', expiryDay: '20' });
  });

  it('@BR-ACUP-105 values are stripped as the service strips them (Java String.strip), not as JavaScript trims', () => {
    expect(changedOf({ lastName: '\u001cKessler\u001f', middleName: '\u001e' })).toEqual([]);
    for (const c of ['\u00a0', '\u2007', '\u202f', '\ufeff']) {
      const found = detect({ lastName: ` Kessler${c} ` });
      expect(found.changedFields, c).toEqual(['lastName']);
      expect(found.changes, c).toEqual({ lastName: `Kessler${c}` });
    }
    expect(detectChanges({ ...unchanged, lastName: 'Kessler' }, { ...STORED, lastName: 'Kessler\u00a0' }).changedFields).toEqual(['lastName']);
  });

  it('@DEF-ACUP-110 text is compared exactly after trimming', () => {
    expect(changedOf({ lastName: 'kessler' })).toEqual(['lastName']);
  });

  it('ACUP-S24 ACUP-S36: status, primary holder and state are upper-cased first', () => {
    expect(changedOf({ activeStatus: 'y', priCardHolderInd: 'y', stateCd: 'nc' })).toEqual([]);
    expect(changedOf({ activeStatus: 'n' })).toEqual(['activeStatus']);
  });

  it('amounts that parse compare as numbers; others always count', () => {
    expect(changedOf({ creditLimit: '2,020.00', cashCreditLimit: '1020' })).toEqual([]);
    expect(changedOf({ creditLimit: '2500.00' })).toEqual(['creditLimit']);
    expect(changedOf({ creditLimit: '1.234' })).toEqual(['creditLimit']);
    expect(changedOf({ ficoScore: '0274' })).toEqual([]);
    expect(changedOf({ ficoScore: 'abc' })).toEqual(['ficoScore']);
  });

  it('ACUP-S44: digit date parts compare as numbers with the stored date', () => {
    expect(changedOf({ expiryMonth: '5', expiryDay: '20' })).toEqual([]);
    expect(changedOf({ openYear: '2026', openMonth: '5', openDay: '7' })).toEqual(['openYear']);
    expect(changedOf({ openMonth: 'xx' })).toEqual(['openYear']);
  });

  it('three empty date parts equal a NULL date', () => {
    const stored: Stored = { ...STORED, reissueYear: '', reissueMonth: '', reissueDay: '' };
    expect(detectChanges({ ...unchanged, reissueYear: '', reissueMonth: '', reissueDay: '' }, stored).units).toEqual([]);
    expect(changedOf({ reissueYear: '', reissueMonth: '', reissueDay: '' })).toEqual(['reissueYear']);
  });

  it('ACUP-S38: the SSN compares as nine digits; a masked value the page never saw counts as changed', () => {
    expect(changedOf({ ssn1: '020', ssn2: '97', ssn3: '3888' })).toEqual([]);
    expect(changedOf({ ssn3: '3889' })).toEqual(['ssn1']);
    const { ssn: _clear, ...noSsn } = STORED;
    expect(detectChanges({ ...unchanged }, noSsn).changedFields).toEqual(['ssn1']);
  });

  it('ACUP-S19 ACUP-S37: ZIP is zip5 plus -zipPlus4 when that is not empty', () => {
    const stored: Stored = { ...STORED, zip: '19852-6716', zip5: '19852', zipPlus4: '6716' };
    expect(detectChanges({ ...unchanged, zip5: '19852', zipPlus4: '6716' }, stored).units).toEqual([]);
    expect(detectChanges({ ...unchanged, zip5: '19852', zipPlus4: '' }, stored).changedFields).toEqual(['zip5']);
  });

  it('ACUP-S35: phones compare part by part; three empty parts equal an empty stored phone', () => {
    expect(changedOf({ phone2Area: '', phone2Prefix: '', phone2Line: '' })).toEqual([]);
    expect(changedOf({ phone1Area: '', phone1Prefix: '', phone1Line: '' })).toEqual(['phone1Area']);
    expect(changedOf({ phone1Prefix: '011' })).toEqual(['phone1Area']);
  });

  it('ACUP-S8: changedFields lists one entry per group in screen order, and changes carry every part', () => {
    const found = detect({ phone2Area: '908', zip5: '27601', ficoScore: '700' });
    expect(found.changedFields).toEqual(['ficoScore', 'zip5', 'phone2Area']);
    expect(found.changes).toEqual({ ficoScore: '700', zip5: '27601', zipPlus4: '', phone2Area: '908', phone2Prefix: '', phone2Line: '' });
  });

  it('only the given units are compared', () => {
    const accountOnly = UNITS.filter((u) => !u.customer);
    const found = detectChanges({ ...unchanged, ficoScore: '700', creditLimit: '2500' }, STORED, accountOnly);
    expect(found.changedFields).toEqual(['creditLimit' satisfies AcupField]);
  });
});
