import { isEmpty, parseAmount } from '@carddemo/validation/rules';
import type { AccountUpdateForm } from '@/api/client';
import { strip } from '@/lib/javaStrip';

/**
 * Account update (COACTUPC) change detection, BR-ACUP-102 as fixed by D-50
 * and D-75 (account-service contract §6, "Change detection"). The page uses
 * it to send only what changed (D-49); the mock service uses it with the
 * stored clear values. The service stays the authority.
 */

export type AcupField =
  | 'activeStatus'
  | 'openYear'
  | 'openMonth'
  | 'openDay'
  | 'creditLimit'
  | 'expiryYear'
  | 'expiryMonth'
  | 'expiryDay'
  | 'cashCreditLimit'
  | 'reissueYear'
  | 'reissueMonth'
  | 'reissueDay'
  | 'groupId'
  | 'ssn1'
  | 'ssn2'
  | 'ssn3'
  | 'dobYear'
  | 'dobMonth'
  | 'dobDay'
  | 'ficoScore'
  | 'firstName'
  | 'middleName'
  | 'lastName'
  | 'addrLine1'
  | 'stateCd'
  | 'addrLine2'
  | 'zip5'
  | 'zipPlus4'
  | 'city'
  | 'phone1Area'
  | 'phone1Prefix'
  | 'phone1Line'
  | 'govtIssuedId'
  | 'phone2Area'
  | 'phone2Prefix'
  | 'phone2Line'
  | 'eftAccountId'
  | 'priCardHolderInd';

export type AcupValues = Record<AcupField, string>;

/** The write-only masked values (D-09): each is replaced as a whole. */
export type MaskedKey = 'ssn' | 'dob' | 'govtIssuedId' | 'eftAccountId';

type Kind = 'text' | 'upper' | 'amount' | 'number' | 'date' | 'ssn' | 'zip' | 'phone';

export interface Unit {
  /** A field, or a group's name as the audit row lists it. */
  name: string;
  /** Screen label, as in FIELD_NOT_EDITABLE. */
  label: string;
  fields: AcupField[];
  kind: Kind;
  customer: boolean;
  masked?: MaskedKey;
}

const one = (name: AcupField, label: string, kind: Kind, customer: boolean, masked?: MaskedKey): Unit => ({ name, label, fields: [name], kind, customer, masked });
const date = (name: string, label: string, prefix: 'open' | 'expiry' | 'reissue' | 'dob', customer: boolean, masked?: MaskedKey): Unit => ({
  name,
  label,
  fields: [`${prefix}Year`, `${prefix}Month`, `${prefix}Day`] as AcupField[],
  kind: 'date',
  customer,
  masked,
});
const phone = (n: 1 | 2): Unit => ({
  name: `phone${n}`,
  label: `Phone Number ${n}`,
  fields: [`phone${n}Area`, `phone${n}Prefix`, `phone${n}Line`] as AcupField[],
  kind: 'phone',
  customer: true,
});

/** The editable fields in screen order (form accountUpdate, FLOW-ACUP-102), one unit per group. */
export const UNITS: Unit[] = [
  one('activeStatus', 'Account Status', 'upper', false),
  date('openDate', 'Open Date', 'open', false),
  one('creditLimit', 'Credit Limit', 'amount', false),
  date('expirationDate', 'Expiry Date', 'expiry', false),
  one('cashCreditLimit', 'Cash Credit Limit', 'amount', false),
  date('reissueDate', 'Reissue Date', 'reissue', false),
  one('groupId', 'Account Group', 'text', false),
  { name: 'ssn', label: 'SSN', fields: ['ssn1', 'ssn2', 'ssn3'], kind: 'ssn', customer: true, masked: 'ssn' },
  date('dob', 'Date of Birth', 'dob', true, 'dob'),
  one('ficoScore', 'FICO Score', 'number', true),
  one('firstName', 'First Name', 'text', true),
  one('middleName', 'Middle Name', 'text', true),
  one('lastName', 'Last Name', 'text', true),
  one('addrLine1', 'Address Line 1', 'text', true),
  one('stateCd', 'State', 'upper', true),
  one('addrLine2', 'Address Line 2', 'text', true),
  { name: 'zip', label: 'Zip', fields: ['zip5', 'zipPlus4'], kind: 'zip', customer: true },
  one('city', 'City', 'text', true),
  phone(1),
  one('govtIssuedId', 'Government Issued Id', 'text', true, 'govtIssuedId'),
  phone(2),
  one('eftAccountId', 'EFT Account Id', 'text', true, 'eftAccountId'),
  one('priCardHolderInd', 'Primary Card Holder', 'upper', true),
];

export const ACUP_FIELDS: AcupField[] = UNITS.flatMap((u) => u.fields);

/** The unit a field belongs to. */
export const unitOf = (field: string): Unit | undefined => UNITS.find((u) => (u.fields as string[]).includes(field));

/**
 * The stored values to compare with: every field's part, plus the stored
 * ZIP, phones and SSN as whole values. A missing entry is unknown (a masked
 * value the page never sees), and any input for it counts as a change.
 */
export type Stored = Partial<Record<AcupField | 'zip' | 'phone1' | 'phone2' | 'ssn', string>>;

/**
 * The value the rules check and the save writes: stripped first, then empty when FormRules
 * counts it as empty (BR-ACUP-105), so " *" is empty too and " 05" is 05.
 */
export const normalise = (value: string | null | undefined): string => {
  const stripped = strip(value ?? '');
  return isEmpty(stripped) ? '' : stripped;
};
const norm = normalise;
const DIGITS = /^[0-9]+$/;

function changed(unit: Unit, input: Partial<AcupValues>, stored: Stored): boolean {
  const parts = unit.fields.map((f) => norm(input[f]));
  const was = unit.fields.map((f) => stored[f]);
  const [value = ''] = parts;
  const [before] = was;
  switch (unit.kind) {
    case 'text':
      return before === undefined || value !== strip(before);
    case 'upper':
      return before === undefined || value.toUpperCase() !== strip(before).toUpperCase();
    case 'amount': {
      if (before === undefined) return true;
      if (value === '') return strip(before) !== '';
      const amount = parseAmount(value, 10, 2);
      return amount === null || Number(amount) !== Number(before);
    }
    case 'number':
      if (before === undefined) return true;
      if (value === '') return strip(before) !== '';
      return !DIGITS.test(value) || Number(value) !== Number(before);
    case 'date': {
      if (was.some((s) => s === undefined)) return true;
      const storedEmpty = was.every((s) => strip(s ?? '') === '');
      if (parts.every((p) => p === '')) return !storedEmpty;
      if (storedEmpty || parts.some((p) => !DIGITS.test(p))) return true;
      return parts.some((p, i) => Number(p) !== Number(was[i]));
    }
    case 'ssn':
      return stored.ssn === undefined || parts.join('') !== stored.ssn;
    case 'zip': {
      if (stored.zip === undefined) return true;
      const [zip5 = '', plus4 = ''] = parts;
      return `${zip5}${plus4 ? `-${plus4}` : ''}` !== strip(stored.zip);
    }
    case 'phone': {
      const whole = stored[unit.name as 'phone1' | 'phone2'];
      if (parts.every((p) => p === '')) return whole === undefined || strip(whole) !== '';
      return was.some((s) => s === undefined) || parts.some((p, i) => p !== strip(was[i] ?? ''));
    }
  }
}

export interface Changes {
  /** Every part of each changed unit, normalised (D-49: changed fields only). */
  changes: Partial<AcupValues>;
  /** The changed units' first fields, in screen order (validate's changedFields). */
  changedFields: AcupField[];
  units: Unit[];
}

/** The units of `units` whose input differs from the stored values. */
export function detectChanges(input: Partial<AcupValues>, stored: Stored, units: readonly Unit[] = UNITS): Changes {
  const hit = UNITS.filter((u) => units.includes(u) && changed(u, input, stored));
  const changes: Partial<AcupValues> = {};
  hit.forEach((u) => u.fields.forEach((f) => (changes[f] = normalise(input[f]))));
  return { changes, changedFields: hit.map((u) => u.fields[0] as AcupField), units: hit };
}

// --- The page's view of the form ----------------------------------------------

const MASKED_UNITS = UNITS.filter((u) => u.masked);

/** The inputs as fetched: masked fields start empty, behind their Change control. */
export function valuesOf(form: AccountUpdateForm): AcupValues {
  const s = storedOf(form);
  return Object.fromEntries(ACUP_FIELDS.map((f) => [f, s[f] ?? ''])) as AcupValues;
}

/** The stored values the page knows; the clear masked values are unknown to it. */
export function storedOf(form: AccountUpdateForm): Stored {
  const v = (x: string | null | undefined) => x ?? '';
  return {
    activeStatus: form.activeStatus,
    openYear: form.openYear,
    openMonth: form.openMonth,
    openDay: form.openDay,
    creditLimit: form.creditLimit,
    expiryYear: form.expiryYear,
    expiryMonth: form.expiryMonth,
    expiryDay: form.expiryDay,
    cashCreditLimit: form.cashCreditLimit,
    reissueYear: form.reissueYear,
    reissueMonth: form.reissueMonth,
    reissueDay: form.reissueDay,
    groupId: form.groupId,
    ficoScore: v(form.ficoScore),
    firstName: v(form.firstName),
    middleName: v(form.middleName),
    lastName: v(form.lastName),
    addrLine1: v(form.addrLine1),
    stateCd: v(form.stateCd),
    addrLine2: v(form.addrLine2),
    zip5: v(form.zip5),
    zipPlus4: v(form.zipPlus4),
    zip: v(form.zip),
    city: v(form.city),
    phone1: v(form.phone1),
    phone1Area: v(form.phone1Area),
    phone1Prefix: v(form.phone1Prefix),
    phone1Line: v(form.phone1Line),
    phone2: v(form.phone2),
    phone2Area: v(form.phone2Area),
    phone2Prefix: v(form.phone2Prefix),
    phone2Line: v(form.phone2Line),
    priCardHolderInd: v(form.priCardHolderInd),
  };
}

/** An account with no card has no customer fields (D-61). */
export const hasCustomer = (form: AccountUpdateForm): boolean => form.custId !== null && form.custId !== undefined;

/** The units the page compares: masked ones only once replaced, customer ones only with a customer. */
export function pageUnits(form: AccountUpdateForm, replaced: ReadonlySet<MaskedKey>): Unit[] {
  const customer = hasCustomer(form);
  return UNITS.filter((u) => (customer || !u.customer) && (!u.masked || replaced.has(u.masked)));
}

/** The masked units, for the Change controls. */
export const maskedUnits = (): Unit[] => MASKED_UNITS;
