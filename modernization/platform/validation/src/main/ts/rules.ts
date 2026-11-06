// The validation rule table engine, mirrored from org.carddemo.platform.validation.FormRules and
// LegacyAmount. It reads the same rules.json and lookups.json as the services, and both are tested
// on src/test/resources/.../rule-vectors.json, so the web app and the services run the same edits.
import { COMPACT, legacyDateCheck } from './legacyDateCheck';

export interface FieldProblem {
  field: string;
  code: string;
  message: string;
}

export interface ValidationResult {
  /** The failed edits, in edit order; the screen shows the first. */
  problems: FieldProblem[];
  /** The marked fields in screen order; the cursor goes to the first. */
  wrongFields: string[];
}

type Json = Record<string, unknown>;

interface Check extends Json {
  kind: string;
  code?: string;
  message?: string;
}

interface Entry extends Json {
  kind?: string;
  name?: string;
  label?: string;
  checks?: Check[];
  requiresValid?: string[];
  parts?: Record<string, string>;
  messages?: Record<string, string>;
}

export interface RulesFile {
  schema: number;
  forms: Record<string, { fields: Entry[] }>;
}

export interface LookupsFile {
  lists: Record<string, string[]>;
}

const DEFAULT_MESSAGES: Record<string, string> = {
  REQUIRED: '{label} must be supplied.',
  TOO_LONG: '{label} must not be longer than {maxLength} characters.',
  ALPHABETIC: '{label} can have alphabets only.',
  NUMERIC: '{label} must be all numeric.',
  NON_ZERO: '{label} must not be zero.',
  YES_NO: '{label} must be Y or N.',
  AMOUNT_INVALID: '{label} is not valid',
  AMOUNT_RANGE: '{label} is not valid',
  RANGE: '{label} is not valid',
  NOT_ALLOWED: '{label} is not valid',
  NOT_IN_LIST: '{label} is not valid',
  DATE_YEAR_REQUIRED: '{label} : Year must be supplied.',
  DATE_YEAR_NOT_4_DIGITS: '{label} must be 4 digit number.',
  DATE_CENTURY: '{label} : Century is not valid.',
  DATE_MONTH_REQUIRED: '{label} : Month must be supplied.',
  DATE_MONTH_INVALID: '{label}: Month must be a number between 1 and 12.',
  DATE_DAY_REQUIRED: '{label} : Day must be supplied.',
  DATE_DAY_INVALID: '{label}:day must be a number between 1 and 31.',
  DATE_31_DAYS: '{label}:Cannot have 31 days in this month.',
  DATE_30_DAYS: '{label}:Cannot have 30 days in this month.',
  DATE_LEAP_YEAR: '{label}:Not a leap year.Cannot have 29 days in this month.',
  DATE_CALENDAR: '{label} validation error Sev code: {severity} Message code: {messageNumber}',
  DATE_FUTURE: '{label}:cannot be in the future ',
  PHONE_AREA_REQUIRED: '{label}: Area code must be supplied.',
  PHONE_AREA_NOT_3_DIGITS: '{label}: Area code must be A 3 digit number.',
  PHONE_AREA_ZERO: '{label}: Area code cannot be zero',
  PHONE_AREA_NOT_GENERAL_PURPOSE: '{label}: Not valid North America general purpose area code',
  PHONE_PREFIX_REQUIRED: '{label}: Prefix code must be supplied.',
  PHONE_PREFIX_NOT_3_DIGITS: '{label}: Prefix code must be A 3 digit number.',
  PHONE_PREFIX_ZERO: '{label}: Prefix code cannot be zero',
  PHONE_LINE_REQUIRED: '{label}: Line number code must be supplied.',
  PHONE_LINE_NOT_4_DIGITS: '{label}: Line number code must be A 4 digit number.',
  PHONE_LINE_ZERO: '{label}: Line number code cannot be zero',
  STATE_ZIP_MISMATCH: 'Invalid zip code for state',
};

const CHECK_KINDS = new Set([
  'required', 'maxLength', 'alphabetic', 'numeric', 'nonZero', 'yesNo', 'range', 'notIn', 'inList', 'amount', 'amountRange',
]);
const LONG_MONTHS = new Set([1, 3, 5, 7, 8, 10, 12]);
const AMOUNT =
  /^ *(?<lead>[+-])? *(?<cur>\$)? *(?<lead2>[+-])? *(?<int>[0-9]{1,3}(?:,[0-9]{3})+|[0-9]*)(?:\.(?<frac>[0-9]*))? *(?<trail>[+-]|CR|DB)? *$/;

/** Spaces only, or * followed by spaces (BR-ACUP-105). */
export const isEmpty = (value: string | null | undefined): boolean => value == null || /^\*? *$/.test(value);

/** Java's String.strip for the characters a form field can hold. */
const strip = (value: string | null | undefined): string => (value ?? '').trim();

const digits = (s: string, length: number): boolean => s.length === length && /^[0-9]*$/.test(s);
const shortNumber = (s: string): boolean => /^[0-9]{1,2}$/.test(s);

/**
 * The amount as a decimal string (sign, integer digits, optional fraction), or null when the text is
 * not valid for maxIntegerDigits integer digits and maxScale decimals. See LegacyAmount.
 */
export function parseAmount(text: string | null | undefined, maxIntegerDigits: number, maxScale: number): string | null {
  if (text == null) return null;
  const m = AMOUNT.exec(text.toUpperCase());
  if (!m?.groups) return null;
  const { lead, lead2, trail } = m.groups;
  if ((lead ? 1 : 0) + (lead2 ? 1 : 0) + (trail ? 1 : 0) > 1) return null;
  const integer = (m.groups.int ?? '').replaceAll(',', '');
  const fraction = m.groups.frac ?? '';
  if (integer === '' && fraction === '') return null;
  const significant = integer.replace(/^0+/, '');
  if (significant.length > maxIntegerDigits || fraction.length > maxScale) return null;
  const negative = (lead ?? lead2) === '-' || trail === '-' || trail === 'CR' || trail === 'DB';
  return `${negative ? '-' : ''}${significant === '' ? '0' : significant}${fraction === '' ? '' : `.${fraction}`}`;
}

/** Compares two decimal strings as numbers. */
function compareDecimal(a: string, b: string): number {
  const parse = (s: string): { neg: boolean; int: string; frac: string } => {
    const neg = s.startsWith('-');
    const [i = '', f = ''] = s.replace(/^[+-]/, '').split('.');
    return { neg, int: i.replace(/^0+/, ''), frac: f.replace(/0+$/, '') };
  };
  const x = parse(a);
  const y = parse(b);
  const zero = (v: { int: string; frac: string }): boolean => v.int === '' && v.frac === '';
  if (zero(x) && zero(y)) return 0;
  const sx = zero(x) ? 0 : x.neg ? -1 : 1;
  const sy = zero(y) ? 0 : y.neg ? -1 : 1;
  if (sx !== sy) return sx < sy ? -1 : 1;
  let mag: number;
  if (x.int.length !== y.int.length) {
    mag = x.int.length < y.int.length ? -1 : 1;
  } else {
    const len = Math.max(x.frac.length, y.frac.length);
    const ax = x.int + x.frac.padEnd(len, '0');
    const ay = y.int + y.frac.padEnd(len, '0');
    mag = ax === ay ? 0 : ax < ay ? -1 : 1;
  }
  return sx * mag;
}

export class FormRules {
  readonly id: string;
  /** Every input field the form edits, in screen order. */
  readonly fields: string[];
  private readonly entries: Entry[];
  private readonly lists: Record<string, string[]>;

  constructor(id: string, entries: Entry[], lists: Record<string, string[]>) {
    this.id = id;
    this.entries = entries;
    this.lists = lists;
    const order: string[] = [];
    const requireList = (name: string): void => {
      if (!(name in lists)) throw new Error(`form ${id}: unknown lookup list ${name}`);
    };
    for (const e of entries) {
      const kind = e.kind ?? 'field';
      if (kind === 'field') {
        order.push(String(e.name));
        for (const c of e.checks ?? []) {
          if (!CHECK_KINDS.has(c.kind)) throw new Error(`form ${id}: unknown check kind ${c.kind}`);
          if (typeof c.list === 'string') requireList(c.list);
        }
      } else if (kind === 'date') {
        order.push(...['year', 'month', 'day'].map((p) => String(e.parts?.[p])));
      } else if (kind === 'phone') {
        order.push(...['area', 'prefix', 'line'].map((p) => String(e.parts?.[p])));
        requireList(String(e.areaList ?? 'nanpGeneralPurposeAreaCodes'));
      } else if (kind === 'stateZip') {
        requireList(String(e.list ?? 'usStateZipPrefixes'));
      } else {
        throw new Error(`form ${id}: unknown entry kind ${kind}`);
      }
    }
    this.fields = order;
  }

  /** Edits the input (field name to text; a missing field is empty). today is YYYY-MM-DD. */
  validate(input: Record<string, string | null | undefined>, today: string): ValidationResult {
    const problems: FieldProblem[] = [];
    const wrong = new Set<string>();
    const passed = new Set<string>();
    const value = (f: string): string => input[f] ?? '';
    const fail = (e: Entry, code: string, label: string, args: Record<string, string>, ...fields: string[]): void => {
      let message = (e.messages?.[code] ?? DEFAULT_MESSAGES[code] ?? '').replaceAll('{label}', label);
      for (const [k, v] of Object.entries(args)) message = message.replaceAll(`{${k}}`, v);
      const first = this.fields.find((f) => fields.includes(f)) ?? fields[0] ?? '';
      problems.push({ field: first, code, message });
      fields.forEach((f) => wrong.add(f));
    };

    const check = (c: Check, raw: string): string | null => {
      const v = strip(raw);
      switch (c.kind) {
        case 'required': {
          const extra = (c.emptyValues as string[] | undefined) ?? [];
          return isEmpty(raw) || extra.includes(v) ? 'REQUIRED' : null;
        }
        case 'maxLength':
          return raw.replace(/\s+$/, '').length > Number(c.length) ? 'TOO_LONG' : null;
        case 'alphabetic': {
          const extra = String(c.extra ?? '');
          return [...raw].every((ch) => /[A-Za-z ]/.test(ch) || extra.includes(ch)) ? null : 'ALPHABETIC';
        }
        case 'numeric':
          return digits(v, Number(c.length)) ? null : 'NUMERIC';
        case 'nonZero':
          return /^0*$/.test(v) ? 'NON_ZERO' : null;
        case 'yesNo': {
          const s = c.ignoreCase ? v.toUpperCase() : v;
          return s === 'Y' || s === 'N' ? null : 'YES_NO';
        }
        case 'range': {
          const n = Number(v);
          return n < Number(c.min) || n > Number(c.max) ? 'RANGE' : null;
        }
        case 'notIn': {
          const n = Number(v);
          const values = (c.values as number[] | undefined) ?? [];
          const ranges = (c.ranges as [number, number][] | undefined) ?? [];
          return values.includes(n) || ranges.some(([lo, hi]) => n >= lo && n <= hi) ? 'NOT_ALLOWED' : null;
        }
        case 'inList': {
          const s = c.ignoreCase ? v.toUpperCase() : v;
          return (this.lists[String(c.list)] ?? []).includes(s) ? null : 'NOT_IN_LIST';
        }
        case 'amount':
          return parseAmount(raw, Number(c.maxIntegerDigits), Number(c.maxScale)) == null ? 'AMOUNT_INVALID' : null;
        case 'amountRange': {
          const n = parseAmount(raw, Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER) ?? '0';
          const low = typeof c.min === 'string' && compareDecimal(n, c.min) < 0;
          const high = typeof c.max === 'string' && compareDecimal(n, c.max) > 0;
          return low || high ? 'AMOUNT_RANGE' : null;
        }
        default:
          throw new Error(`unknown check kind ${c.kind}`);
      }
    };

    const field = (e: Entry): void => {
      const name = String(e.name);
      const label = String(e.label);
      if ((e.requiresValid ?? []).some((dep) => !passed.has(dep))) return;
      const raw = value(name);
      const checks = e.checks ?? [];
      const required = checks[0]?.kind === 'required';
      if (!required && isEmpty(raw)) {
        passed.add(name);
        return;
      }
      for (const c of checks) {
        const failure = check(c, raw);
        if (failure != null) {
          const template = c.message ?? DEFAULT_MESSAGES[failure] ?? '';
          const message = template.replaceAll('{label}', label).replaceAll('{maxLength}', c.length == null ? '' : String(c.length));
          problems.push({ field: name, code: c.code ?? failure, message });
          wrong.add(name);
          return;
        }
      }
      passed.add(name);
    };

    const date = (e: Entry): void => {
      const label = String(e.label);
      const yf = String(e.parts?.year);
      const mf = String(e.parts?.month);
      const df = String(e.parts?.day);
      const y = value(yf);
      const m = value(mf);
      const d = value(df);
      let yearOk = false;
      let monthOk = false;
      let dayOk = false;
      if (isEmpty(y)) fail(e, 'DATE_YEAR_REQUIRED', label, {}, yf);
      else if (!digits(strip(y), 4)) fail(e, 'DATE_YEAR_NOT_4_DIGITS', label, {}, yf);
      else {
        const year = Number(strip(y));
        if (year < Number(e.minYear ?? 1900) || year > Number(e.maxYear ?? 2099)) fail(e, 'DATE_CENTURY', label, {}, yf);
        else yearOk = true;
      }
      if (isEmpty(m)) fail(e, 'DATE_MONTH_REQUIRED', label, {}, mf);
      else if (!shortNumber(strip(m)) || Number(strip(m)) < 1 || Number(strip(m)) > 12) fail(e, 'DATE_MONTH_INVALID', label, {}, mf);
      else monthOk = true;
      if (isEmpty(d)) fail(e, 'DATE_DAY_REQUIRED', label, {}, df);
      else if (!shortNumber(strip(d)) || Number(strip(d)) < 1 || Number(strip(d)) > 31) fail(e, 'DATE_DAY_INVALID', label, {}, df);
      else dayOk = true;
      if (!(yearOk && monthOk && dayOk)) return;
      const year = Number(strip(y));
      const month = Number(strip(m));
      const day = Number(strip(d));
      const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
      if (day === 31 && !LONG_MONTHS.has(month)) return fail(e, 'DATE_31_DAYS', label, {}, mf, df);
      if (month === 2 && day === 30) return fail(e, 'DATE_30_DAYS', label, {}, mf, df);
      if (month === 2 && day === 29 && !leap) return fail(e, 'DATE_LEAP_YEAR', label, {}, yf, mf, df);
      const iso = `${String(year).padStart(4, '0')}${String(month).padStart(2, '0')}${String(day).padStart(2, '0')}`;
      const r = legacyDateCheck(iso, COMPACT);
      if (r.severity !== 0) {
        const four = (n: number): string => String(n).padStart(4, '0');
        return fail(e, 'DATE_CALENDAR', label, { severity: four(r.severity), messageNumber: four(r.messageNumber) }, yf, mf, df);
      }
      if (e.notFuture === true && r.value != null) {
        const future = e.allowToday === true ? r.value > today : r.value >= today;
        if (future) fail(e, 'DATE_FUTURE', label, {}, yf, mf, df);
      }
    };

    const phone = (e: Entry): void => {
      const label = String(e.label);
      const af = String(e.parts?.area);
      const pf = String(e.parts?.prefix);
      const lf = String(e.parts?.line);
      const a = value(af);
      const p = value(pf);
      const l = value(lf);
      if (isEmpty(a) && isEmpty(p) && isEmpty(l) && e.required !== true) return;
      const areaCodes = this.lists[String(e.areaList ?? 'nanpGeneralPurposeAreaCodes')] ?? [];
      if (isEmpty(a)) fail(e, 'PHONE_AREA_REQUIRED', label, {}, af);
      else if (!digits(strip(a), 3)) fail(e, 'PHONE_AREA_NOT_3_DIGITS', label, {}, af);
      else if (strip(a) === '000') fail(e, 'PHONE_AREA_ZERO', label, {}, af);
      else if (!areaCodes.includes(strip(a))) fail(e, 'PHONE_AREA_NOT_GENERAL_PURPOSE', label, {}, af);
      if (isEmpty(p)) fail(e, 'PHONE_PREFIX_REQUIRED', label, {}, pf);
      else if (!digits(strip(p), 3)) fail(e, 'PHONE_PREFIX_NOT_3_DIGITS', label, {}, pf);
      else if (strip(p) === '000') fail(e, 'PHONE_PREFIX_ZERO', label, {}, pf);
      if (isEmpty(l)) fail(e, 'PHONE_LINE_REQUIRED', label, {}, lf);
      else if (!digits(strip(l), 4)) fail(e, 'PHONE_LINE_NOT_4_DIGITS', label, {}, lf);
      else if (strip(l) === '0000') fail(e, 'PHONE_LINE_ZERO', label, {}, lf);
    };

    const stateZip = (e: Entry): void => {
      const sf = String(e.state);
      const zf = String(e.zip);
      if (!passed.has(sf) || !passed.has(zf) || isEmpty(value(sf)) || isEmpty(value(zf))) return;
      let state = strip(value(sf));
      if (e.ignoreCase === true) state = state.toUpperCase();
      const zip = strip(value(zf));
      if (!(this.lists[String(e.list ?? 'usStateZipPrefixes')] ?? []).includes(state + zip.slice(0, 2))) {
        fail(e, 'STATE_ZIP_MISMATCH', '', {}, sf, zf);
      }
    };

    for (const e of this.entries) {
      const kind = e.kind ?? 'field';
      if (kind === 'field') field(e);
      else if (kind === 'date') date(e);
      else if (kind === 'phone') phone(e);
      else if (kind === 'stateZip') stateZip(e);
    }
    return { problems, wrongFields: this.fields.filter((f) => wrong.has(f)) };
  }
}

export class RuleTable {
  private readonly forms: Map<string, FormRules>;
  private readonly lists: Record<string, string[]>;

  constructor(rules: RulesFile, lookups: LookupsFile) {
    if (rules.schema !== 1) throw new Error('rules: schema 1 expected');
    this.lists = lookups.lists;
    this.forms = new Map(Object.entries(rules.forms).map(([id, f]) => [id, new FormRules(id, f.fields, this.lists)]));
  }

  form(id: string): FormRules {
    const form = this.forms.get(id);
    if (!form) throw new Error(`no form ${id} in the rule table; forms: ${[...this.forms.keys()].join(', ')}`);
    return form;
  }

  get formIds(): string[] {
    return [...this.forms.keys()];
  }

  list(name: string): string[] {
    const list = this.lists[name];
    if (!list) throw new Error(`no lookup list ${name}`);
    return list;
  }
}
