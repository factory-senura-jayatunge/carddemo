import { strip, stripTrailing } from '@/lib/javaStrip';
import type { MessageKey } from '@/messages';
import { parseAmount } from '@carddemo/validation/rules';
import { checkAccountUpdateId, checkAccountViewId, checkCardFilters, checkCardKeys, isBlankFilter, toProblem } from '@/validation/accountRules';
import { detectChanges, unitOf, UNITS, type AcupField, type AcupValues, type Changes, type Stored } from '@/validation/accountUpdate';
import { form, today } from '@/validation/forms';
import { sampleAccounts, sampleCards, sampleCustomers, sampleXref, type SampleCard, type SampleCustomer, type SampleXref } from './accountData';
import { decimal, json, problem, readBody, ruleProblem, text } from './http';
import { fillMessage, messages } from './messages';

/**
 * account-service in mock mode: account view (§5), account update (§6), card list (§7), card view
 * (§8) and card update (§9) of the account-service contract with the
 * architect's rulings, in their check order.
 */

export interface CoreAccount {
  acctId: string;
  activeStatus: string;
  creditLimit: number;
  cashCreditLimit: number;
  openDate: string;
  expirationDate: string;
  reissueDate: string;
  currCycCredit: number;
  currCycDebit: number;
  groupId: string;
  version: number;
}
export type CoreCustomer = SampleCustomer & { version: number };
export type CoreCard = SampleCard & { version: number };
export interface AuditRow {
  actor: string;
  action: 'UNMASK' | 'UPDATE' | 'CREATE';
  subjectType: 'ACCOUNT' | 'CARD' | 'TRANSACTION' | 'DAILY_TRAN';
  subjectKey: string;
  fieldNames: string;
}
export interface CoreData {
  accounts: Record<string, CoreAccount>;
  customers: Record<string, CoreCustomer>;
  cards: CoreCard[];
  xref: SampleXref[];
  audit: AuditRow[];
}

/**
 * The sample data, plus three accounts that exist only in mock mode, with the
 * balances of the Bill Pay fixtures: 00000000098 has no card (D-61: "No card
 * is linked to this account.") and the card of 00000000099 names a customer
 * that does not exist (CUSTOMER_NOT_FOUND). The cross-reference of
 * 00000000097 names customer 000000097, whose long name, blank middle name and
 * double-spaced address exercise the authorization summary's display cuts.
 */
export function initialCore(): CoreData {
  const accounts: Record<string, CoreAccount> = {};
  sampleAccounts.forEach(({ currBal: _balance, ...account }, index) => {
    accounts[account.acctId] = { ...account, version: 2001 + index };
  });
  const fixture = (acctId: string, version: number): CoreAccount => ({
    acctId,
    activeStatus: 'Y',
    creditLimit: 100_000,
    cashCreditLimit: 50_000,
    openDate: '2020-01-15',
    expirationDate: '2027-01-31',
    reissueDate: '2027-01-31',
    currCycCredit: 0,
    currCycDebit: 0,
    groupId: '',
    version,
  });
  accounts['00000000098'] = fixture('00000000098', 2098);
  accounts['00000000099'] = fixture('00000000099', 2099);
  accounts['00000000097'] = fixture('00000000097', 2097);
  const customers: Record<string, CoreCustomer> = {};
  sampleCustomers.forEach((customer, index) => {
    customers[customer.custId] = { ...customer, version: 3001 + index };
  });
  customers['000000097'] = {
    custId: '000000097',
    firstName: 'Maximiliana',
    middleName: '',
    lastName: 'Vandenberghe-Oppenheimer',
    addrLine1: '12  Long Acre Road',
    addrLine2: 'Unit 4',
    city: 'Saint  Petersburg Beach',
    stateCd: 'FL',
    countryCd: 'USA',
    zip: '33706-1234',
    phone1: '(727)555-0197  ',
    phone2: '',
    ssn: '000000097',
    govtIssuedId: '',
    dob: '1980-01-01',
    eftAccountId: '',
    priCardHolderInd: 'Y',
    ficoScore: 700,
    version: 3097,
  };
  return {
    accounts,
    customers,
    cards: sampleCards.map((card, index) => ({ ...card, version: 4001 + index })),
    xref: [
      ...sampleXref,
      { cardNum: '9999000000000099', custId: '000000099', acctId: '00000000099' },
      { cardNum: '9999000000000097', custId: '000000097', acctId: '00000000097' },
    ],
    audit: [],
  };
}

export interface AccountContext {
  request: Request;
  /** Balances in cents by account ID, shared with Bill Pay. */
  balances: Record<string, number>;
  core: CoreData;
  userId: string;
  role: 'ADMIN' | 'USER';
  faults: readonly string[];
  batchWindowOpen: boolean;
}

// D-09: every character but the last four masked, separators of the display form kept.
const maskTail = (value: string) => (value === '' ? '' : value.length <= 4 ? '****' : `${'*'.repeat(value.length - 4)}${value.slice(-4)}`);
export const maskPan = maskTail;
const ssnDisplay = (ssn: string) => (ssn.length === 9 ? `${ssn.slice(0, 3)}-${ssn.slice(3, 5)}-${ssn.slice(5)}` : ssn);
const maskSsn = (ssn: string) => (ssn === '' ? '' : `***-**-${ssn.slice(-4)}`);
const maskDob = (dob: string) => (dob === '' ? '' : `****-${dob.slice(5)}`);

/** The legacy picture +ZZZ,ZZZ,ZZZ.99, widened to +Z,ZZZ,ZZZ,ZZZ.99 at a billion or more (NUM-ACVW-001, DEF-ACVW-005). */
export function legacyAmount(cents: number): string {
  const abs = Math.abs(cents);
  const units = Math.floor(abs / 100);
  const grouped = units === 0 ? '' : units.toLocaleString('en-US');
  const width = abs >= 100_000_000_000 ? 13 : 11;
  return `${cents < 0 ? '-' : '+'}${grouped.padStart(width)}.${String(abs % 100).padStart(2, '0')}`;
}

const amount = (name: string, cents: number) => ({ [name]: decimal(cents), [`${name}Display`]: legacyAmount(cents) });
const version = (n: number) => n.toString(16).padStart(16, '0');

// §2: lower-case hex, 16 digits per row; If-Match quoted, a body version without quotes.
const TAG = /^[0-9a-f]{16}(?:\.[0-9a-f]{16})*$/;
const ifMatchTag = (header: string) => {
  const v = strip(header);
  return v.length >= 2 && v.startsWith('"') && v.endsWith('"') && TAG.test(v.slice(1, -1)) ? v.slice(1, -1) : null;
};
const bodyTag = (value: string) => (strip(value) === '' ? null : ifMatchTag(`"${value}"`));
const etag = (v: string) => ({ ETag: `"${v}"` });
const NO_STORE = { 'Cache-Control': 'no-store', Pragma: 'no-cache' };
const accessDenied = () => problem(403, 'ACCESS_DENIED', null);

function accountFields(account: CoreAccount, balance: number) {
  return {
    acctId: account.acctId,
    activeStatus: account.activeStatus,
    openDate: account.openDate,
    expirationDate: account.expirationDate,
    reissueDate: account.reissueDate,
    ...amount('creditLimit', account.creditLimit),
    ...amount('cashCreditLimit', account.cashCreditLimit),
    ...amount('currentBalance', balance),
    ...amount('currCycCredit', account.currCycCredit),
    ...amount('currCycDebit', account.currCycDebit),
    groupId: account.groupId,
  };
}

function customerFields(c: CoreCustomer) {
  return {
    custId: c.custId,
    ssn: maskSsn(c.ssn),
    dob: maskDob(c.dob),
    ficoScore: String(c.ficoScore).padStart(3, '0'),
    firstName: c.firstName,
    middleName: c.middleName,
    lastName: c.lastName,
    addrLine1: c.addrLine1,
    addrLine2: c.addrLine2,
    city: c.city,
    stateCd: c.stateCd,
    zip: c.zip,
    countryCd: c.countryCd,
    phone1: c.phone1,
    phone2: c.phone2,
    govtIssuedId: maskTail(c.govtIssuedId),
    eftAccountId: maskTail(c.eftAccountId),
    priCardHolderInd: c.priCardHolderInd,
  };
}

/** D-61: the cross-reference row of the account's lowest card number. */
const lowestXref = (core: CoreData, acctId: string) =>
  core.xref.filter((x) => x.acctId === acctId).sort((a, b) => (a.cardNum < b.cardNum ? -1 : 1))[0];
const cardByNum = (core: CoreData, cardNum: string) => core.cards.find((c) => c.cardNum === cardNum);

type Read<T> = { value: T } | { response: Response };

/** Account read steps of view and unmask: account, then cross-reference, then customer (D-61). */
function readAccount(ctx: AccountContext, acctId: string): Read<{ account: CoreAccount; xref?: SampleXref; customer?: CoreCustomer }> {
  const fault = (name: string) => ctx.faults.includes(name);
  if (fault('account-read')) return { response: problem(500, 'ACCOUNT_DATA_READ_FAILED', 'acctId') };
  const account = ctx.core.accounts[acctId];
  if (!account) return { response: problem(404, 'ACCOUNT_ID_NOT_FOUND', 'acctId', {}, undefined, { acctId }) };
  if (fault('xref-read')) return { response: problem(500, 'XREF_DATA_READ_FAILED', 'acctId') };
  const xref = lowestXref(ctx.core, acctId);
  if (!xref) return { value: { account } };
  if (fault('customer-read')) return { response: problem(500, 'CUSTOMER_LOOKUP_FAILED', 'acctId') };
  return { value: { account, xref, customer: ctx.core.customers[xref.custId] } };
}

function viewAccount(ctx: AccountContext, acctId: string): Response {
  const key = checkAccountViewId(acctId);
  if (key) return ruleProblem(400, key);
  const read = readAccount(ctx, acctId);
  if ('response' in read) return read.response;
  const { account, xref, customer } = read.value;
  const base = accountFields(account, ctx.balances[acctId] ?? 0);
  if (!xref) {
    return json(200, {
      ...base,
      crossReference: null,
      customer: null,
      code: 'NO_CARD_LINKED',
      message: fillMessage('NO_CARD_LINKED'),
      severity: 'info',
      field: 'acctId',
    });
  }
  // Ruling on Q-ACVW-004: a customer the cross-reference names but that does not exist stops the view.
  if (!customer) return problem(404, 'CUSTOMER_NOT_FOUND', 'acctId', {}, undefined, { custId: xref.custId });
  const card = cardByNum(ctx.core, xref.cardNum);
  return json(200, {
    ...base,
    crossReference: { custId: xref.custId, cardId: card ? String(card.cardId) : null, cardNum: maskPan(xref.cardNum) },
    customer: customerFields(customer),
    code: null,
    message: null,
    severity: null,
    field: null,
  });
}

const ACCOUNT_UNMASKABLE = ['ssn', 'dob', 'govtIssuedId', 'eftAccountId', 'cardNum'];

function requestedFields(body: Record<string, unknown>, allowed: string[]): string[] | null {
  const fields = body.fields;
  if (!Array.isArray(fields) || fields.length === 0) return null;
  if (!fields.every((f): f is string => typeof f === 'string' && allowed.includes(f))) return null;
  return [...new Set(fields)];
}

function audit(ctx: AccountContext, row: Omit<AuditRow, 'actor'>): Response | null {
  if (ctx.faults.includes('audit')) return problem(500, 'UNMASK_AUDIT_FAILED', null);
  ctx.core.audit.push({ actor: ctx.userId, ...row });
  return null;
}

async function unmaskAccount(ctx: AccountContext, acctId: string): Promise<Response> {
  if (ctx.role !== 'ADMIN') return accessDenied();
  const key = checkAccountViewId(acctId);
  if (key) return ruleProblem(400, key);
  const fields = requestedFields(await readBody(ctx.request), ACCOUNT_UNMASKABLE);
  if (!fields) return problem(400, 'INVALID_UNMASK_FIELD', 'fields', {}, undefined, { fields: ACCOUNT_UNMASKABLE.join(', ') });
  const read = readAccount(ctx, acctId);
  if ('response' in read) return read.response;
  const { xref, customer } = read.value;
  const failed = audit(ctx, { action: 'UNMASK', subjectType: 'ACCOUNT', subjectKey: acctId, fieldNames: [...fields].sort().join(',') });
  if (failed) return failed;
  const clear: Record<string, string | null> = {
    ssn: customer ? ssnDisplay(customer.ssn) : null,
    dob: customer ? customer.dob : null,
    govtIssuedId: customer ? customer.govtIssuedId : null,
    eftAccountId: customer ? customer.eftAccountId : null,
    cardNum: xref ? xref.cardNum : null,
  };
  return json(200, { fields: Object.fromEntries(fields.map((f) => [f, clear[f] ?? null])) }, NO_STORE);
}

// --- Account update (§6) ---------------------------------------------------

const acctRowVersion = (ctx: AccountContext, account: CoreAccount) => {
  // Any change to the account row blocks a save (approved); the balance lives in Bill Pay's store, so it is part of the version.
  const balance = (((ctx.balances[account.acctId] ?? 0) % 2 ** 32) + 2 ** 32) % 2 ** 32;
  return `${account.version.toString(16).padStart(8, '0')}${balance.toString(16).padStart(8, '0')}`;
};
const formVersion = (ctx: AccountContext, account: CoreAccount, customer?: CoreCustomer) =>
  customer ? `${acctRowVersion(ctx, account)}.${version(customer.version)}` : acctRowVersion(ctx, account);

const dateParts = (prefix: string, value: string) => ({ [`${prefix}Year`]: value.slice(0, 4), [`${prefix}Month`]: value.slice(5, 7), [`${prefix}Day`]: value.slice(8, 10) });
const phoneParts = (n: 1 | 2, value: string) => ({
  [`phone${n}`]: value,
  [`phone${n}Area`]: value.slice(1, 4),
  [`phone${n}Prefix`]: value.slice(5, 8),
  [`phone${n}Line`]: value.slice(9, 13),
});
const zipParts = (zip: string) => ({ zip, zip5: zip.slice(0, 5), zipPlus4: zip[5] === '-' ? zip.slice(6, 10) : '' });

const NO_CUSTOMER = Object.fromEntries(
  ['custId', 'ssn', 'dob', 'ficoScore', 'firstName', 'middleName', 'lastName', 'addrLine1', 'addrLine2', 'city', 'stateCd', 'zip', 'zip5', 'zipPlus4', 'countryCd', 'phone1', 'phone1Area', 'phone1Prefix', 'phone1Line', 'phone2', 'phone2Area', 'phone2Prefix', 'phone2Line', 'govtIssuedId', 'eftAccountId', 'priCardHolderInd'].map((f) => [f, null]),
);

/** AccountUpdateForm: PII masked (D-09), date parts, ZIP and phone parts (D-14), the group from group_id (D-11). */
function updateForm(ctx: AccountContext, account: CoreAccount, xref?: SampleXref, customer?: CoreCustomer) {
  const card = xref ? cardByNum(ctx.core, xref.cardNum) : undefined;
  return {
    acctId: account.acctId,
    cardId: card ? String(card.cardId) : null,
    activeStatus: account.activeStatus,
    ...dateParts('open', account.openDate),
    ...dateParts('expiry', account.expirationDate),
    ...dateParts('reissue', account.reissueDate),
    ...amount('creditLimit', account.creditLimit),
    ...amount('cashCreditLimit', account.cashCreditLimit),
    ...amount('currentBalance', ctx.balances[account.acctId] ?? 0),
    ...amount('currCycCredit', account.currCycCredit),
    ...amount('currCycDebit', account.currCycDebit),
    groupId: account.groupId,
    ...(customer
      ? {
          ...customerFields(customer),
          ...zipParts(customer.zip),
          ...phoneParts(1, customer.phone1),
          ...phoneParts(2, customer.phone2),
        }
      : NO_CUSTOMER),
    version: formVersion(ctx, account, customer),
    message: null,
    severity: null,
  };
}

/** The stored clear values, to compare with and to fill unchanged fields for the cross-field rules (D-49). */
function storedClear(account: CoreAccount, customer?: CoreCustomer): Stored {
  const parts = (prefix: string, value: string) => dateParts(prefix, value) as Stored;
  const base: Stored = {
    activeStatus: account.activeStatus,
    ...parts('open', account.openDate),
    creditLimit: decimal(account.creditLimit),
    ...parts('expiry', account.expirationDate),
    cashCreditLimit: decimal(account.cashCreditLimit),
    ...parts('reissue', account.reissueDate),
    groupId: account.groupId,
  };
  if (!customer) return base;
  return {
    ...base,
    ssn: customer.ssn,
    ssn1: customer.ssn.slice(0, 3),
    ssn2: customer.ssn.slice(3, 5),
    ssn3: customer.ssn.slice(5, 9),
    ...parts('dob', customer.dob),
    ficoScore: String(customer.ficoScore),
    firstName: customer.firstName,
    middleName: customer.middleName,
    lastName: customer.lastName,
    addrLine1: customer.addrLine1,
    stateCd: customer.stateCd,
    addrLine2: customer.addrLine2,
    ...(zipParts(customer.zip) as Stored),
    city: customer.city,
    ...(phoneParts(1, customer.phone1) as Stored),
    govtIssuedId: customer.govtIssuedId,
    ...(phoneParts(2, customer.phone2) as Stored),
    eftAccountId: customer.eftAccountId,
    priCardHolderInd: customer.priCardHolderInd,
  };
}

const READ_ONLY_LABELS: Record<string, string> = {
  acctId: 'Account Number',
  custId: 'Customer id',
  countryCd: 'Country',
  currentBalance: 'Current Balance',
  currCycCredit: 'Current Cycle Credit',
  currCycDebit: 'Current Cycle Debit',
};

interface UpdateRequest {
  account: CoreAccount;
  xref?: SampleXref;
  customer?: CoreCustomer;
  found: Changes;
}

/** Validate steps 1 and 3 to 5 (fetch reads, editable names, change detection, rules); the version is the caller's. */
function checkUpdate(ctx: AccountContext, acctId: string, body: Record<string, unknown>, checkVersion?: (v: string) => Response | null): Read<UpdateRequest> {
  const key = checkAccountUpdateId(acctId);
  if (key) return { response: ruleProblem(400, key) };
  const read = readForUpdate(ctx, acctId);
  if ('response' in read) return read;
  const { account, xref, customer } = read.value;
  const stale = checkVersion?.(formVersion(ctx, account, customer));
  if (stale) return { response: stale };
  const raw = typeof body.changes === 'object' && body.changes !== null ? (body.changes as Record<string, unknown>) : {};
  for (const name of Object.keys(raw)) {
    const unit = unitOf(name);
    const label = unit?.label ?? READ_ONLY_LABELS[name];
    // A name outside the fixed label table is never echoed: not as the label, not as the field.
    if (!label) return { response: problem(400, 'FIELD_NOT_EDITABLE_UNKNOWN', 'changes') };
    if (!unit || (unit.customer && !customer)) {
      return { response: problem(400, 'FIELD_NOT_EDITABLE', name, {}, undefined, { label }) };
    }
  }
  const input = Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, text(v)])) as Partial<AcupValues>;
  const stored = storedClear(account, customer);
  const touched = UNITS.filter((u) => u.fields.some((f) => f in raw));
  const found = detectChanges(input, stored, touched);
  if (found.units.length === 0) return { response: problem(400, 'NO_CHANGE', 'activeStatus') };
  const failed = toProblem(form('accountUpdate').validate(found.changes, today(), found.changedFields, stored));
  if (failed) return { response: ruleProblem(400, failed, { invalidFields: failed.invalid }) };
  return { value: { account, xref, customer, found } };
}

/** Fetch steps 2 to 4 with the §6 texts. */
function readForUpdate(ctx: AccountContext, acctId: string): Read<{ account: CoreAccount; xref?: SampleXref; customer?: CoreCustomer }> {
  const read = readAccount(ctx, acctId);
  if ('response' in read) return read;
  const { xref, customer } = read.value;
  if (xref && !customer) return { response: problem(404, 'CUSTOMER_NOT_FOUND', 'acctId', {}, undefined, { custId: xref.custId }) };
  return read;
}

/** An account update version names the account, and the customer when there is one. */
const accountTag = (tag: string | null) => (tag !== null && tag.split('.').length <= 2 ? tag : null);

function accountChanged(ctx: AccountContext, acctId: string): Response {
  const read = readForUpdate(ctx, acctId);
  if ('response' in read) return read.response;
  const { account, xref, customer } = read.value;
  const current = updateForm(ctx, account, xref, customer);
  return problem(409, 'RECORD_CHANGED', 'activeStatus', { current }, etag(current.version));
}

function fetchAccountForUpdate(ctx: AccountContext, acctId: string): Response {
  if (ctx.role !== 'USER') return accessDenied();
  const key = checkAccountUpdateId(acctId);
  if (key) return ruleProblem(400, key);
  const read = readForUpdate(ctx, acctId);
  if ('response' in read) return read.response;
  const { account, xref, customer } = read.value;
  const result = updateForm(ctx, account, xref, customer);
  return json(200, result, etag(result.version));
}

async function validateAccountUpdate(ctx: AccountContext, acctId: string): Promise<Response> {
  if (ctx.role !== 'USER') return accessDenied();
  const body = await readBody(ctx.request);
  const checked = checkUpdate(ctx, acctId, body, (current) => {
    const sent = accountTag(bodyTag(text(body.version)));
    if (!sent) return problem(400, 'INVALID_IF_MATCH', null);
    return sent === current ? null : accountChanged(ctx, acctId);
  });
  if ('response' in checked) return checked.response;
  return json(200, { message: fillMessage('CHANGES_VALIDATED'), severity: 'info', changedFields: checked.value.found.changedFields });
}

const pad2 = (s: string) => s.padStart(2, '0');
const dateOf = (c: Partial<AcupValues>, prefix: 'open' | 'expiry' | 'reissue' | 'dob') => {
  const [y, m, d] = (['Year', 'Month', 'Day'] as const).map((p) => strip(c[`${prefix}${p}` as AcupField] ?? ''));
  return y === '' && m === '' && d === '' ? '' : `${y}-${pad2(m ?? '')}-${pad2(d ?? '')}`;
};
const cents = (value: string) => Math.round(Number(parseAmount(strip(value), 10, 2)) * 100);

/** Written values (§6): upper-cased flags and state, trimmed text, dates from parts, ZIP and phone layouts. */
function applyChanges(account: CoreAccount, customer: CoreCustomer | undefined, found: Changes): void {
  const c = found.changes;
  const t = (f: AcupField) => strip(c[f] ?? '');
  for (const unit of found.units) {
    switch (unit.name) {
      case 'activeStatus': account.activeStatus = t('activeStatus').toUpperCase(); break;
      case 'openDate': account.openDate = dateOf(c, 'open'); break;
      case 'creditLimit': account.creditLimit = cents(t('creditLimit')); break;
      case 'expirationDate': account.expirationDate = dateOf(c, 'expiry'); break;
      case 'cashCreditLimit': account.cashCreditLimit = cents(t('cashCreditLimit')); break;
      case 'reissueDate': account.reissueDate = dateOf(c, 'reissue'); break;
      case 'groupId': account.groupId = t('groupId'); break;
      default: {
        if (!customer) break;
        if (unit.name === 'ssn') customer.ssn = `${t('ssn1')}${t('ssn2')}${t('ssn3')}`;
        else if (unit.name === 'dob') customer.dob = dateOf(c, 'dob');
        else if (unit.name === 'ficoScore') customer.ficoScore = Number(t('ficoScore'));
        else if (unit.name === 'zip') customer.zip = t('zipPlus4') ? `${t('zip5')}-${t('zipPlus4')}` : t('zip5');
        else if (unit.kind === 'phone') {
          const [a, p, l] = unit.fields.map(t);
          const value = a || p || l ? `(${a})${p}-${l}` : '';
          if (unit.name === 'phone1') customer.phone1 = value;
          else customer.phone2 = value;
        } else {
          const field = unit.name as 'firstName' | 'middleName' | 'lastName' | 'addrLine1' | 'addrLine2' | 'city' | 'stateCd' | 'govtIssuedId' | 'eftAccountId' | 'priCardHolderInd';
          customer[field] = unit.kind === 'upper' ? t(field).toUpperCase() : t(field);
        }
      }
    }
  }
}

async function updateAccount(ctx: AccountContext, acctId: string): Promise<Response> {
  if (ctx.role !== 'USER') return accessDenied();
  const key = checkAccountUpdateId(acctId);
  if (key) return ruleProblem(400, key);
  const ifMatch = ctx.request.headers.get('If-Match');
  if (ifMatch === null || strip(ifMatch) === '') return problem(428, 'PRECONDITION_REQUIRED', null);
  const sent = accountTag(ifMatchTag(ifMatch));
  if (!sent) return problem(400, 'INVALID_IF_MATCH', null);
  const checked = checkUpdate(ctx, acctId, await readBody(ctx.request));
  if ('response' in checked) return checked.response;
  const { account, xref, customer, found } = checked.value;
  if (ctx.batchWindowOpen) return problem(503, 'ACCOUNT_UPDATE_BATCH_WINDOW', 'activeStatus', {}, { 'Retry-After': '300' });
  if (ctx.faults.includes('account-lock')) return problem(409, 'ACCOUNT_LOCK_FAILED', 'acctId');
  if (customer && ctx.faults.includes('customer-lock')) return problem(409, 'CUSTOMER_LOCK_FAILED', 'acctId');
  if (sent !== formVersion(ctx, account, customer)) return accountChanged(ctx, acctId);
  if (ctx.faults.includes('account-update') || ctx.faults.includes('audit')) return problem(500, 'ACCOUNT_SAVE_FAILED', 'acctId');
  // D-16: both rows or neither; each row's version moves only when one of its columns changed.
  const nextAccount = { ...account };
  const nextCustomer = customer ? { ...customer } : undefined;
  applyChanges(nextAccount, nextCustomer, found);
  if (found.units.some((u) => !u.customer)) nextAccount.version += 1;
  if (nextCustomer && found.units.some((u) => u.customer)) nextCustomer.version += 1;
  ctx.core.accounts[acctId] = nextAccount;
  if (nextCustomer) ctx.core.customers[nextCustomer.custId] = nextCustomer;
  ctx.core.audit.push({ actor: ctx.userId, action: 'UPDATE', subjectType: 'ACCOUNT', subjectKey: acctId, fieldNames: found.units.map((u) => u.name).sort().join(',') });
  const saved = updateForm(ctx, nextAccount, xref, nextCustomer);
  return json(200, { message: fillMessage('CHANGES_COMMITTED'), severity: messages.CHANGES_COMMITTED.severity, form: saved }, etag(saved.version));
}
// --- Cards -----------------------------------------------------------------

const token = (cardId: number) => btoa(String(cardId)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const MAX_BIGINT = 9223372036854775807n;
/** A positive BIGINT card ID, as the path or a token carries it. */
const isCardId = (id: string) => /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= MAX_BIGINT;
/** The card ID of a canonical token only: "!!", padded, "0", "01", "-1" or beyond BIGINT are not tokens. */
function untoken(value: unknown): string | null {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) return null;
  try {
    const id = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
    return isCardId(id) && token(Number(id)) === value ? id : null;
  } catch {
    return null;
  }
}
const isBlankCursor = (value: unknown) => value === undefined || value === null || (typeof value === 'string' && strip(value) === '');
/** Only a JSON string is a typed filter; any other JSON value is invalid, never blank. */
const filterText = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : '?');
const cardRow = (c: CoreCard) => ({ cardId: String(c.cardId), acctId: c.acctId, cardNum: maskPan(c.cardNum), activeStatus: c.activeStatus });

async function listCards(ctx: AccountContext): Promise<Response> {
  const body = await readBody(ctx.request);
  const cursors = (['start', 'after', 'before'] as const).filter((k) => !isBlankCursor(body[k]));
  if (cursors.length > 1) return problem(400, 'CARD_LIST_INVALID_CURSOR', 'acctId');
  const [kind] = cursors;
  const cursorId = kind ? untoken(body[kind]) : null;
  if (kind && !cursorId) return problem(400, 'CARD_LIST_INVALID_CURSOR', 'acctId');
  const size = body.size ?? 7;
  if (typeof size !== 'number' || !Number.isInteger(size) || size < 1 || size > 7) {
    return problem(400, 'INVALID_PAGE_SIZE', 'acctId', {}, undefined, { max: '7' });
  }
  const acctFilter = filterText(body.acctId);
  const cardFilter = filterText(body.cardNum);
  const bad = checkCardFilters(acctFilter, cardFilter);
  if (bad) return ruleProblem(400, bad);
  if (ctx.faults.includes('card-read')) return problem(500, 'CARD_LIST_FAILED', 'acctId');
  // A token whose card is gone is found by the page read, after the size and filter checks (v8).
  const cursor = cursorId ? ctx.core.cards.find((c) => String(c.cardId) === cursorId) : null;
  if (kind && !cursor) return problem(400, 'CARD_LIST_INVALID_CURSOR', 'acctId');

  const matches = ctx.core.cards
    .filter((c) => (isBlankFilter(acctFilter) || c.acctId === acctFilter) && (isBlankFilter(cardFilter) || c.cardNum === cardFilter))
    .sort((a, b) => (a.cardNum < b.cardNum ? -1 : 1));
  const key = cursor?.cardNum ?? '';
  let rows: CoreCard[];
  if (kind === 'start') rows = matches.filter((c) => c.cardNum >= key).slice(0, size);
  else if (kind === 'after') rows = matches.filter((c) => c.cardNum > key).slice(0, size);
  else if (kind === 'before') {
    const earlier = matches.filter((c) => c.cardNum < key);
    // DEF-CRDL-009: fewer than a page before the cursor gives the first page.
    rows = earlier.length === 0 ? [] : earlier.length < size ? matches.slice(0, size) : earlier.slice(-size);
  } else rows = matches.slice(0, size);

  const first = rows[0];
  const last = rows.at(-1);
  const hasNext = last ? matches.some((c) => c.cardNum > last.cardNum) : false;
  const hasPrevious = first ? matches.some((c) => c.cardNum < first.cardNum) : false;
  const notice: MessageKey | null =
    rows.length === 0
      ? kind === 'after'
        ? 'NO_MORE_RECORDS'
        : kind === 'before'
          ? 'NO_PREVIOUS_PAGES'
          : 'NO_RECORDS_FOUND'
      : hasNext
        ? null
        : 'NO_MORE_RECORDS';
  return json(200, {
    items: rows.map(cardRow),
    hasNext,
    hasPrevious,
    firstKey: first ? token(first.cardId) : null,
    lastKey: last ? token(last.cardId) : null,
    message: notice ? fillMessage(notice) : null,
    severity: notice ? 'info' : null,
  });
}

const cardView = (c: CoreCard) => ({
  cardId: String(c.cardId),
  acctId: c.acctId,
  cardNum: maskPan(c.cardNum),
  embossedName: c.embossedName,
  activeStatus: c.activeStatus,
  expiryMonth: c.expirationDate.slice(5, 7),
  expiryYear: c.expirationDate.slice(0, 4),
});

const cardForm = (c: CoreCard) => ({ ...cardView(c), version: version(c.version), message: null, severity: null });

/** Card view and card update lookups by the typed keys (D-13: the card must belong to the account, by the cross-reference). */
async function cardByKeys(ctx: AccountContext): Promise<Read<CoreCard>> {
  const body = await readBody(ctx.request);
  const acctId = text(body.acctId);
  const cardNum = text(body.cardNum);
  const bad = checkCardKeys(acctId, cardNum);
  if (bad) return { response: ruleProblem(400, bad) };
  if (ctx.faults.includes('card-read')) return { response: problem(500, 'CARD_LOOKUP_FAILED', 'acctId') };
  const linked = ctx.core.xref.some((x) => x.cardNum === cardNum && x.acctId === acctId);
  const card = linked ? cardByNum(ctx.core, cardNum) : undefined;
  return card ? { value: card } : { response: problem(404, 'CARD_NOT_FOUND', 'acctId') };
}

function cardById(ctx: AccountContext, cardId: string): Read<CoreCard> {
  if (!isCardId(cardId)) return { response: problem(404, 'CARD_NOT_FOUND', 'acctId') };
  if (ctx.faults.includes('card-read')) return { response: problem(500, 'CARD_LOOKUP_FAILED', 'acctId') };
  const card = ctx.core.cards.find((c) => String(c.cardId) === cardId);
  return card ? { value: card } : { response: problem(404, 'CARD_NOT_FOUND', 'acctId') };
}

const shown = (c: CoreCard) => json(200, { ...cardView(c), message: fillMessage('CARD_DISPLAYED'), severity: 'info' });

async function unmaskCard(ctx: AccountContext, cardId: string): Promise<Response> {
  if (ctx.role !== 'ADMIN') return accessDenied();
  const fields = requestedFields(await readBody(ctx.request), ['cardNum']);
  if (!fields) return problem(400, 'INVALID_UNMASK_FIELD', 'fields', {}, undefined, { fields: 'cardNum' });
  const read = cardById(ctx, cardId);
  if ('response' in read) return read.response;
  const failed = audit(ctx, { action: 'UNMASK', subjectType: 'CARD', subjectKey: String(read.value.cardId), fieldNames: 'cardNum' });
  return failed ?? json(200, { fields: { cardNum: read.value.cardNum } }, NO_STORE);
}

interface CardChanges {
  embossedName: string;
  activeStatus: string;
  expiryMonth: string;
  expiryYear: string;
}

function cardChanges(body: Record<string, unknown>): CardChanges {
  const changes = (typeof body.changes === 'object' && body.changes !== null ? body.changes : {}) as Record<string, unknown>;
  return {
    embossedName: text(changes.embossedName),
    // Compared and written as the rules see them: " y" is Y, " 05" is 05 (S9, S36).
    activeStatus: strip(text(changes.activeStatus)),
    expiryMonth: strip(text(changes.expiryMonth)),
    expiryYear: strip(text(changes.expiryYear)),
  };
}

const isNoChange = (card: CoreCard, ch: CardChanges) =>
  stripTrailing(ch.embossedName) === stripTrailing(card.embossedName) &&
  ch.activeStatus.toUpperCase() === card.activeStatus &&
  ch.expiryMonth === card.expirationDate.slice(5, 7) &&
  ch.expiryYear === card.expirationDate.slice(0, 4);

/** Form cardUpdate: every field is edited; the stored expiry lets an expired card keep its date (D-51). */
function cardRuleProblem(card: CoreCard, ch: CardChanges): Response | null {
  const stored = { expiryMonth: card.expirationDate.slice(5, 7), expiryYear: card.expirationDate.slice(0, 4) };
  const failed = toProblem(form('cardUpdate').validate({ ...ch }, today(), null, stored));
  return failed ? ruleProblem(400, failed, { invalidFields: failed.invalid }) : null;
}

/** A card version names the card row only. */
const cardTag = (tag: string | null) => (tag !== null && !tag.includes('.') ? tag : null);

const recordChanged = (card: CoreCard) =>
  problem(409, 'RECORD_CHANGED', 'embossedName', { current: cardForm(card) }, etag(version(card.version)));

/** expiration_date from the typed month and year and the stored day, clamped to the month (D-51). */
function expiryDate(card: CoreCard, ch: CardChanges): string {
  const year = Number(ch.expiryYear);
  const month = Number(ch.expiryMonth);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const day = Math.min(Number(card.expirationDate.slice(8, 10)) || 1, lastDay);
  return `${ch.expiryYear}-${ch.expiryMonth}-${String(day).padStart(2, '0')}`;
}

async function validateCard(ctx: AccountContext, cardId: string): Promise<Response> {
  if (ctx.role !== 'USER') return accessDenied();
  const read = cardById(ctx, cardId);
  if ('response' in read) return read.response;
  const card = read.value;
  const body = await readBody(ctx.request);
  const sent = cardTag(bodyTag(text(body.version)));
  if (!sent) return problem(400, 'INVALID_IF_MATCH', null);
  if (sent !== version(card.version)) return recordChanged(card);
  const ch = cardChanges(body);
  if (isNoChange(card, ch)) return problem(400, 'NO_CHANGE', 'embossedName');
  return cardRuleProblem(card, ch) ?? json(200, { message: fillMessage('CHANGES_VALIDATED'), severity: 'info' });
}

async function updateCard(ctx: AccountContext, cardId: string): Promise<Response> {
  if (ctx.role !== 'USER') return accessDenied();
  if (!isCardId(cardId)) return problem(404, 'CARD_NOT_FOUND', 'acctId');
  const ifMatch = ctx.request.headers.get('If-Match');
  if (ifMatch === null || strip(ifMatch) === '') return problem(428, 'PRECONDITION_REQUIRED', null);
  const sent = cardTag(ifMatchTag(ifMatch));
  if (!sent) return problem(400, 'INVALID_IF_MATCH', null);
  const ch = cardChanges(await readBody(ctx.request));
  const card = ctx.core.cards.find((c) => String(c.cardId) === cardId);
  if (card) {
    if (isNoChange(card, ch)) return problem(400, 'NO_CHANGE', 'embossedName');
    const rules = cardRuleProblem(card, ch);
    if (rules) return rules;
  }
  if (ctx.batchWindowOpen) return problem(503, 'CARD_UPDATE_BATCH_WINDOW', 'embossedName', {}, { 'Retry-After': '300' });
  if (!card || ctx.faults.includes('card-lock')) return problem(409, 'CARD_LOCK_FAILED', 'acctId');
  if (sent !== version(card.version)) return recordChanged(card);
  if (ctx.faults.includes('card-update') || ctx.faults.includes('audit')) return problem(500, 'CARD_UPDATE_FAILED', 'acctId');
  const next = { embossedName: stripTrailing(ch.embossedName), activeStatus: ch.activeStatus.toUpperCase(), expirationDate: expiryDate(card, ch) };
  const changed = (['activeStatus', 'embossedName', 'expirationDate'] as const).filter((f) => next[f] !== card[f]);
  Object.assign(card, next, { version: card.version + 1 });
  ctx.core.audit.push({ actor: ctx.userId, action: 'UPDATE', subjectType: 'CARD', subjectKey: String(card.cardId), fieldNames: changed.join(',') });
  return json(200, { message: fillMessage('CHANGES_COMMITTED'), severity: messages.CHANGES_COMMITTED.severity, form: cardForm(card) }, etag(version(card.version)));
}

/** Answers the account-service paths, or null for a path it does not own. */
export async function routeAccounts(method: string, path: string, ctx: AccountContext): Promise<Response | null> {
  if (ctx.faults.includes('unexpected')) {
    const correlationId = crypto.randomUUID();
    return problem(500, 'INTERNAL_ERROR', null, { correlationId }, { 'X-Correlation-Id': correlationId });
  }
  const account = /^\/api\/accounts\/([^/]*)(\/unmask|\/update-form|\/validate)?$/.exec(path);
  if (account) {
    const acctId = decodeURIComponent(account[1] ?? '');
    const sub = account[2];
    if (!sub && method === 'GET') return viewAccount(ctx, acctId);
    if (!sub && method === 'PUT') return updateAccount(ctx, acctId);
    if (sub === '/unmask' && method === 'POST') return unmaskAccount(ctx, acctId);
    if (sub === '/update-form' && method === 'GET') return fetchAccountForUpdate(ctx, acctId);
    if (sub === '/validate' && method === 'POST') return validateAccountUpdate(ctx, acctId);
    return null;
  }
  if (path === '/api/cards/search' && method === 'POST') return listCards(ctx);
  if (path === '/api/cards/lookup' && method === 'POST') {
    const read = await cardByKeys(ctx);
    return 'response' in read ? read.response : shown(read.value);
  }
  if (path === '/api/cards/update-lookup' && method === 'POST') {
    if (ctx.role !== 'USER') return accessDenied();
    const read = await cardByKeys(ctx);
    return 'response' in read ? read.response : json(200, cardForm(read.value), etag(version(read.value.version)));
  }
  const card = /^\/api\/cards\/([^/]*)(\/update-form|\/validate|\/unmask)?$/.exec(path);
  if (!card) return null;
  const cardId = decodeURIComponent(card[1] ?? '');
  const sub = card[2];
  if (!sub && method === 'GET') {
    const read = cardById(ctx, cardId);
    return 'response' in read ? read.response : shown(read.value);
  }
  if (!sub && method === 'PUT') return updateCard(ctx, cardId);
  if (sub === '/update-form' && method === 'GET') {
    if (ctx.role !== 'USER') return accessDenied();
    const read = cardById(ctx, cardId);
    return 'response' in read ? read.response : json(200, cardForm(read.value), etag(version(read.value.version)));
  }
  if (sub === '/validate' && method === 'POST') return validateCard(ctx, cardId);
  if (sub === '/unmask' && method === 'POST') return unmaskCard(ctx, cardId);
  return null;
}
