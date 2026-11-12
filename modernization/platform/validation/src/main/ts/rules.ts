// The validation rule table engine, mirrored from org.carddemo.platform.validation.FormRules and
// LegacyAmount. It reads the same rules.json and lookups.json as the services, and both are tested
// on src/test/resources/.../rule-vectors.json, so the web app and the services run the same edits.
//
// A field's trim option ({leading, trailing}: sets of UTF-16 units) sets the value its checks see:
// the raw text without those characters at the end, then at the start; without trim it is Java's
// strip, and maxLength measures stripTrailing. That value is read by required (and emptyValues),
// latin1, numeric without exact, nonZero, yesNo, range, notIn, inList, noSpace, codePoints and, with
// trim, maxLength. alphabetic, numeric with exact, amount and amountRange read the raw text.
// codePoints counts a lone surrogate as one code point, as Java's codePointCount does. noSpace,
// codePoints and the differs entry have no default text: the rule gives code and message.
import { legacyUpper } from './legacyCase';
import { strip as javaStrip, stripTrailing } from './javaStrip';
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
  code?: string;
  message?: string;
}

export interface RulesFile {
  schema: number;
  forms: Record<string, { fields: Entry[]; groups?: string[][] }>;
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
  DATE_ORDER: '{label} is not valid',
  PHONE_AREA_REQUIRED: '{label}: Area code must be supplied.',
  PHONE_AREA_NOT_3_DIGITS: '{label}: Area code must be A 3 digit number.',
  PHONE_AREA_ZERO: '{label}: Area code cannot be zero',
  PHONE_AREA_NOT_GENERAL_PURPOSE: '{label}: Not valid North America general purpose area code',
  PHONE_PREFIX_REQUIRED: '{label}: Prefix code must be supplied.',
  PHONE_PREFIX_NOT_3_DIGITS: '{label}: Prefix code must be A 3 digit number.',
  PHONE_PREFIX_ZERO: '{label}: Prefix code cannot be zero',
  PHONE_PREFIX_INVALID: '{label}: Prefix code must not start with 0 or 1',
  PHONE_LINE_REQUIRED: '{label}: Line number code must be supplied.',
  PHONE_LINE_NOT_4_DIGITS: '{label}: Line number code must be A 4 digit number.',
  PHONE_LINE_ZERO: '{label}: Line number code cannot be zero',
  STATE_ZIP_MISMATCH: 'Invalid zip code for state',
  NOT_ABOVE: '{label} must not be more than the {limitLabel}.',
  MONTH_YEAR_PAST: '{label} cannot be in the past',
  NO_INPUT: 'No input received',
  UNSUPPORTED_CHARACTER: '{label} contains a character that is not supported.',
};

const CHECK_KINDS = new Set([
  'required', 'maxLength', 'alphabetic', 'numeric', 'nonZero', 'yesNo', 'range', 'notIn', 'inList', 'amount', 'amountRange', 'latin1',
  'noSpace', 'codePoints',
]);
/** Check kinds with no default code or text: the rule gives both. */
const OWN_TEXT_CHECKS = new Set(['noSpace', 'codePoints']);
const FOLD_LEGACY_UPPER = 'legacyUpper';
const LONG_MONTHS = new Set([1, 3, 5, 7, 8, 10, 12]);
const AMOUNT =
  /^ *(?<lead>[+-])? *(?<cur>\$)? *(?<lead2>[+-])? *(?<int>[0-9]{1,3}(?:,[0-9]{3})+|[0-9]*)(?:\.(?<frac>[0-9]*))? *(?<trail>[+-]|CR|DB)? *$/;

/** Spaces only, or * followed by spaces (BR-ACUP-105). */
export const isEmpty = (value: string | null | undefined): boolean => value == null || /^\*? *$/.test(value);

const strip = (value: string | null | undefined): string => javaStrip(value ?? '');

const digits = (s: string, length: number): boolean => s.length === length && /^[0-9]*$/.test(s);
const shortNumber = (s: string): boolean => /^[0-9]{1,2}$/.test(s);

/** The value as a number when it is 1 to 9 digits after trimming, else null. */
const numberOf = (value: string | null | undefined): number | null => (/^[0-9]{1,9}$/.test(strip(value)) ? Number(strip(value)) : null);

/** Empty for a field: only its blankChars when it has them (CRDL filters), else isEmpty. */
const isBlankFor = (e: Entry, raw: string): boolean =>
  typeof e.blankChars === 'string' ? [...raw].every((ch) => (e.blankChars as string).includes(ch)) : isEmpty(raw);

const parts = (e: Entry, ...names: string[]): string[] => names.map((p) => String(e.parts?.[p]));

/** The value a plain field's checks see: without its trim characters, else Java's strip. */
const trimmed = (e: Entry, raw: string | null | undefined): string => {
  const trim = e.trim as { leading?: string; trailing?: string } | undefined;
  if (trim == null) return strip(raw);
  const s = raw ?? '';
  const leading = trim.leading ?? '';
  const trailing = trim.trailing ?? '';
  let end = s.length;
  while (end > 0 && trailing.includes(s.charAt(end - 1))) end--;
  let start = 0;
  while (start < end && leading.includes(s.charAt(start))) start++;
  return s.slice(start, end);
};

/** ISO control code points, as Java's Character.isISOControl. */
const isIsoControl = (cp: number): boolean => cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f);

const requireText = (form: string, kind: string, node: Json): void => {
  if (typeof node.code !== 'string' || typeof node.message !== 'string') throw new Error(`form ${form}: ${kind} needs a code and a message`);
};

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
  /** Per entry, the fields it holds: an entry runs under changed-fields validation when one changed. */
  private readonly held: string[][];
  private readonly lists: Record<string, string[]>;
  private readonly groups: string[][];
  private readonly fieldEntries = new Map<string, Entry>();
  private readonly dates = new Map<string, Entry>();

  constructor(id: string, entries: Entry[], lists: Record<string, string[]>, groups: string[][] = []) {
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
        this.fieldEntries.set(String(e.name), e);
        if (e.trim !== undefined) {
          const trim = e.trim;
          const ok = typeof trim === 'object' && trim !== null && !Array.isArray(trim) &&
            Object.entries(trim).every(([k, v]) => (k === 'leading' || k === 'trailing') && typeof v === 'string');
          if (!ok) throw new Error(`form ${id}: field ${String(e.name)}: trim takes leading and trailing strings`);
        }
        for (const c of e.checks ?? []) {
          if (!CHECK_KINDS.has(c.kind)) throw new Error(`form ${id}: unknown check kind ${c.kind}`);
          if (OWN_TEXT_CHECKS.has(c.kind)) requireText(id, c.kind, c);
          if (typeof c.list === 'string') requireList(c.list);
        }
      } else if (kind === 'date') {
        order.push(...parts(e, 'year', 'month', 'day'));
        if (typeof e.id === 'string') this.dates.set(e.id, e);
      } else if (kind === 'phone') {
        order.push(...parts(e, 'area', 'prefix', 'line'));
        requireList(String(e.areaList ?? 'nanpGeneralPurposeAreaCodes'));
      } else if (kind === 'stateZip') {
        requireList(String(e.list ?? 'usStateZipPrefixes'));
      } else if (kind === 'dateOrder') {
        if (e.min == null && e.max == null) throw new Error(`form ${id}: dateOrder needs min or max`);
      } else if (kind === 'differs') {
        requireText(id, kind, e);
        if (e.fold !== undefined && e.fold !== FOLD_LEGACY_UPPER) throw new Error(`form ${id}: differs fold must be ${FOLD_LEGACY_UPPER}`);
      } else if (!['notAbove', 'monthYearNotPast', 'anyEntered'].includes(kind)) {
        throw new Error(`form ${id}: unknown entry kind ${kind}`);
      }
    }
    this.held = entries.map((e) => {
      const kind = e.kind ?? 'field';
      let fields: string[];
      if (kind === 'field') fields = [String(e.name)];
      else if (kind === 'date') fields = parts(e, 'year', 'month', 'day');
      else if (kind === 'phone') fields = parts(e, 'area', 'prefix', 'line');
      else if (kind === 'stateZip') fields = [String(e.state), String(e.zip)];
      else if (kind === 'notAbove') fields = [String(e.field), String(e.limit)];
      else if (kind === 'differs') fields = [String(e.field), String(e.other)];
      else if (kind === 'dateOrder') {
        fields = (['date', 'min', 'max'] as const)
          .filter((k) => e[k] != null)
          .flatMap((k) => {
            const date = this.dates.get(String(e[k]));
            if (!date) throw new Error(`form ${id}: unknown date id ${String(e[k])}`);
            return parts(date, 'year', 'month', 'day');
          });
      } else if (kind === 'monthYearNotPast') fields = parts(e, 'month', 'year');
      else fields = ((e.fields as string[] | undefined) ?? []).map(String);
      const needsEntry = kind === 'notAbove' || kind === 'anyEntered' || kind === 'differs';
      for (const f of fields) {
        if (needsEntry ? !this.fieldEntries.has(f) : !order.includes(f)) throw new Error(`form ${id}: ${kind} names unknown field ${f}`);
      }
      return fields;
    });
    for (const g of groups) {
      for (const f of g) if (!order.includes(f)) throw new Error(`form ${id}: group names unknown field ${f}`);
    }
    this.groups = groups;
    this.fields = order;
  }

  /**
   * The value the checks of the plain field see for raw: without its trim characters, or stripped
   * when it has none. A page compares two inputs of the same field (a confirmation) on this value.
   */
  value(field: string, raw: string | null | undefined): string {
    const e = this.fieldEntries.get(field);
    if (!e) throw new Error(`the form has no field ${field}`);
    return trimmed(e, raw);
  }

  /**
   * Edits the input (field name to text; a missing field is empty). today is YYYY-MM-DD. With
   * changedFields only the entries that hold a changed field run, and an unchanged field is valid and
   * takes its value from stored (D-49); without it every entry runs on input. See FormRules.java.
   */
  validate(
    input: Record<string, string | null | undefined>,
    today: string,
    changedFields?: readonly string[] | null,
    stored: Record<string, string | null | undefined> = {},
  ): ValidationResult {
    const changed = changedFields == null ? null : this.expand(changedFields);
    const problems: FieldProblem[] = [];
    const wrong = new Set<string>();
    const passed = new Set<string>(changed == null ? [] : this.fields.filter((f) => !changed.has(f)));
    const value = (f: string): string => (changed == null || changed.has(f) ? input[f] : stored[f]) ?? '';
    const label = (f: string): string => String(this.fieldEntries.get(f)?.label);
    const report = (code: string, template: string, lbl: string, args: Record<string, string>, fields: string[]): void => {
      let message = template.replaceAll('{label}', lbl);
      for (const [k, v] of Object.entries(args)) message = message.replaceAll(`{${k}}`, v);
      const first = this.fields.find((f) => fields.includes(f)) ?? fields[0] ?? '';
      problems.push({ field: first, code, message });
      fields.forEach((f) => wrong.add(f));
    };
    const fail = (e: Entry, code: string, lbl: string, args: Record<string, string>, ...fields: string[]): void =>
      report(code, e.messages?.[code] ?? DEFAULT_MESSAGES[code] ?? '', lbl, args, fields);
    const failEntry = (e: Entry, defaultCode: string, lbl: string, args: Record<string, string>, fields: string[]): void =>
      report(e.code ?? defaultCode, e.message ?? DEFAULT_MESSAGES[defaultCode] ?? '', lbl, args, fields);

    const check = (e: Entry, c: Check, raw: string, blank: boolean): string | null => {
      const v = trimmed(e, raw);
      switch (c.kind) {
        case 'required': {
          const extra = (c.emptyValues as string[] | undefined) ?? [];
          return blank || extra.includes(v) ? 'REQUIRED' : null;
        }
        case 'maxLength':
          return (e.trim != null ? v : stripTrailing(raw)).length > Number(c.length) ? 'TOO_LONG' : null;
        case 'noSpace':
          return v.includes(' ') ? 'NO_SPACE' : null;
        case 'codePoints': {
          const cps = [...v];
          const n = cps.length;
          const shortOrLong = (c.min != null && n < Number(c.min)) || (c.max != null && n > Number(c.max));
          const control = c.printable === true && cps.some((ch) => isIsoControl(ch.codePointAt(0) ?? 0));
          return shortOrLong || control ? 'CODE_POINTS' : null;
        }
        case 'latin1':
          for (let i = 0; i < v.length; i++) if (v.charCodeAt(i) > 0xff) return 'UNSUPPORTED_CHARACTER';
          return null;
        case 'alphabetic': {
          const extra = String(c.extra ?? '');
          return [...raw].every((ch) => /[A-Za-z ]/.test(ch) || extra.includes(ch)) ? null : 'ALPHABETIC';
        }
        case 'numeric':
          return digits(c.exact === true ? raw : v, Number(c.length)) ? null : 'NUMERIC';
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

    /** The first failed check of a field entry and its default code, or null when every check passed. */
    const firstFailure = (e: Entry, raw: string): { check: Check; code: string; defaultCode: string } | null => {
      const checks = e.checks ?? [];
      const blank = isBlankFor(e, raw);
      if (checks[0]?.kind !== 'required' && blank) return null;
      for (const c of checks) {
        const failure = check(e, c, raw, blank);
        if (failure != null) return { check: c, code: c.code ?? failure, defaultCode: failure };
      }
      return null;
    };

    const field = (e: Entry): void => {
      const name = String(e.name);
      if ((e.requiresValid ?? []).some((dep) => !passed.has(dep))) return;
      const failure = firstFailure(e, value(name));
      if (failure == null) {
        passed.add(name);
        return;
      }
      const c = failure.check;
      const template = c.message ?? DEFAULT_MESSAGES[failure.defaultCode] ?? '';
      const message = template.replaceAll('{label}', String(e.label)).replaceAll('{maxLength}', c.length == null ? '' : String(c.length));
      problems.push({ field: name, code: failure.code, message });
      wrong.add(name);
    };

    const date = (e: Entry): void => {
      const before = problems.length;
      const lbl = String(e.label);
      const [yf, mf, df] = parts(e, 'year', 'month', 'day');
      const y = value(yf);
      const m = value(mf);
      const d = value(df);
      let yearOk = false;
      let monthOk = false;
      let dayOk = false;
      if (isEmpty(y)) fail(e, 'DATE_YEAR_REQUIRED', lbl, {}, yf);
      else if (!digits(strip(y), 4)) fail(e, 'DATE_YEAR_NOT_4_DIGITS', lbl, {}, yf);
      else {
        const year = Number(strip(y));
        if (year < Number(e.minYear ?? 1900) || year > Number(e.maxYear ?? 2099)) fail(e, 'DATE_CENTURY', lbl, {}, yf);
        else yearOk = true;
      }
      if (isEmpty(m)) fail(e, 'DATE_MONTH_REQUIRED', lbl, {}, mf);
      else if (!shortNumber(strip(m)) || Number(strip(m)) < 1 || Number(strip(m)) > 12) fail(e, 'DATE_MONTH_INVALID', lbl, {}, mf);
      else monthOk = true;
      if (isEmpty(d)) fail(e, 'DATE_DAY_REQUIRED', lbl, {}, df);
      else if (!shortNumber(strip(d)) || Number(strip(d)) < 1 || Number(strip(d)) > 31) fail(e, 'DATE_DAY_INVALID', lbl, {}, df);
      else dayOk = true;
      if (!(yearOk && monthOk && dayOk)) return;
      const year = Number(strip(y));
      const month = Number(strip(m));
      const day = Number(strip(d));
      const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
      if (day === 31 && !LONG_MONTHS.has(month)) return fail(e, 'DATE_31_DAYS', lbl, {}, mf, df);
      if (month === 2 && day === 30) return fail(e, 'DATE_30_DAYS', lbl, {}, mf, df);
      if (month === 2 && day === 29 && !leap) return fail(e, 'DATE_LEAP_YEAR', lbl, {}, yf, mf, df);
      const iso = `${String(year).padStart(4, '0')}${String(month).padStart(2, '0')}${String(day).padStart(2, '0')}`;
      const r = legacyDateCheck(iso, COMPACT);
      if (r.severity !== 0) {
        const four = (n: number): string => String(n).padStart(4, '0');
        return fail(e, 'DATE_CALENDAR', lbl, { severity: four(r.severity), messageNumber: four(r.messageNumber) }, yf, mf, df);
      }
      if (e.notFuture === true && r.value != null) {
        const future = e.allowToday === true ? r.value > today : r.value >= today;
        if (future) fail(e, 'DATE_FUTURE', lbl, {}, yf, mf, df);
      }
      if (problems.length === before) [yf, mf, df].forEach((f) => passed.add(f));
    };

    const phone = (e: Entry): void => {
      const before = problems.length;
      const lbl = String(e.label);
      const [af, pf, lf] = parts(e, 'area', 'prefix', 'line');
      const a = value(af);
      const p = value(pf);
      const l = value(lf);
      if (isEmpty(a) && isEmpty(p) && isEmpty(l) && e.required !== true) {
        [af, pf, lf].forEach((f) => passed.add(f));
        return;
      }
      const areaCodes = this.lists[String(e.areaList ?? 'nanpGeneralPurposeAreaCodes')] ?? [];
      if (isEmpty(a)) fail(e, 'PHONE_AREA_REQUIRED', lbl, {}, af);
      else if (!digits(strip(a), 3)) fail(e, 'PHONE_AREA_NOT_3_DIGITS', lbl, {}, af);
      else if (strip(a) === '000') fail(e, 'PHONE_AREA_ZERO', lbl, {}, af);
      else if (!areaCodes.includes(strip(a))) fail(e, 'PHONE_AREA_NOT_GENERAL_PURPOSE', lbl, {}, af);
      if (isEmpty(p)) fail(e, 'PHONE_PREFIX_REQUIRED', lbl, {}, pf);
      else if (!digits(strip(p), 3)) fail(e, 'PHONE_PREFIX_NOT_3_DIGITS', lbl, {}, pf);
      else if (strip(p) === '000') fail(e, 'PHONE_PREFIX_ZERO', lbl, {}, pf);
      else if (e.nanpPrefix === true && strip(p).charAt(0) < '2') fail(e, 'PHONE_PREFIX_INVALID', lbl, {}, pf);
      if (isEmpty(l)) fail(e, 'PHONE_LINE_REQUIRED', lbl, {}, lf);
      else if (!digits(strip(l), 4)) fail(e, 'PHONE_LINE_NOT_4_DIGITS', lbl, {}, lf);
      else if (strip(l) === '0000') fail(e, 'PHONE_LINE_ZERO', lbl, {}, lf);
      if (problems.length === before) [af, pf, lf].forEach((f) => passed.add(f));
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

    const notAbove = (e: Entry): void => {
      const f = String(e.field);
      const l = String(e.limit);
      if (!passed.has(f) || !passed.has(l)) return;
      const amount = parseAmount(value(f), Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER);
      const limit = parseAmount(value(l), Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER);
      if (amount != null && limit != null && compareDecimal(amount, limit) > 0) {
        failEntry(e, 'NOT_ABOVE', label(f), { limitLabel: label(l) }, [f, l]);
      }
    };

    /** The date as YYYY-MM-DD when its parts passed (or did not change) and form a calendar date. */
    const validDate = (dateEntry: Entry | undefined): string | null => {
      if (!dateEntry) return null;
      const ps = parts(dateEntry, 'year', 'month', 'day');
      if (!ps.every((p) => passed.has(p))) return null;
      const [y, m, d] = ps.map((p) => strip(value(p)));
      if (!digits(y, 4) || !shortNumber(m) || !shortNumber(d)) return null;
      const year = Number(y);
      const month = Number(m);
      const day = Number(d);
      const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
      const length = month === 2 ? (leap ? 29 : 28) : LONG_MONTHS.has(month) ? 31 : 30;
      if (month < 1 || month > 12 || day < 1 || day > length) return null;
      return `${y}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    };

    /** A stored NULL date: every part unchanged (so counted as passed) and empty. */
    const nullDate = (dateEntry: Entry): boolean => {
      const ps = parts(dateEntry, 'year', 'month', 'day');
      return ps.every((p) => passed.has(p) && isEmpty(value(p)));
    };

    const dateOrder = (e: Entry): void => {
      const target = this.dates.get(String(e.date));
      const d = validDate(target);
      if (d == null || target == null) return;
      const minEntry = e.min != null ? this.dates.get(String(e.min)) : undefined;
      const maxEntry = e.max != null ? this.dates.get(String(e.max)) : undefined;
      // A NULL bound is left out of the comparison; an invalid one already has its own problem.
      const useMin = minEntry != null && !nullDate(minEntry);
      const useMax = maxEntry != null && !nullDate(maxEntry);
      const min = useMin ? validDate(minEntry) : null;
      const max = useMax ? validDate(maxEntry) : null;
      if ((!useMin && !useMax) || (useMin && min == null) || (useMax && max == null)) return;
      const exclusive = e.exclusive === true;
      const low = min != null && (exclusive ? d <= min : d < min);
      const high = max != null && (exclusive ? d >= max : d > max);
      if (low || high) failEntry(e, 'DATE_ORDER', String(target.label), {}, parts(target, 'year', 'month', 'day'));
    };

    const monthYearNotPast = (e: Entry): void => {
      const [mf, yf] = parts(e, 'month', 'year');
      if (!passed.has(mf) || !passed.has(yf)) return;
      const month = numberOf(value(mf));
      const year = numberOf(value(yf));
      if (month == null || year == null) return;
      if (month === numberOf(stored[mf]) && year === numberOf(stored[yf])) return;
      if (year * 12 + month < Number(today.slice(0, 4)) * 12 + Number(today.slice(5, 7))) {
        failEntry(e, 'MONTH_YEAR_PAST', String(e.label ?? ''), {}, [mf, yf]);
      }
    };

    /** False when the field is blank, or its edits stop with the code of its required check. */
    const entered = (e: Entry | undefined, raw: string): boolean => {
      if (!e) return false;
      const checks = e.checks ?? [];
      if (checks[0]?.kind !== 'required') return !isBlankFor(e, raw);
      const failure = firstFailure(e, raw);
      return failure == null || failure.code !== (checks[0].code ?? 'REQUIRED');
    };

    const differs = (e: Entry): void => {
      const f = String(e.field);
      const o = String(e.other);
      const fe = this.fieldEntries.get(f);
      const oe = this.fieldEntries.get(o);
      if (!fe || !oe || !passed.has(f) || !passed.has(o) || isBlankFor(fe, value(f))) return;
      let a = trimmed(fe, value(f));
      let b = trimmed(oe, value(o));
      if (e.fold !== undefined) {
        a = legacyUpper(a);
        b = legacyUpper(b);
      }
      if (a === b) failEntry(e, 'DIFFERS', label(f), {}, [f]);
    };

    const anyEntered = (e: Entry): void => {
      const fields = ((e.fields as string[] | undefined) ?? []).map(String);
      if (fields.some((f) => entered(this.fieldEntries.get(f), value(f)))) return;
      failEntry(e, 'NO_INPUT', '', {}, fields);
    };

    this.entries.forEach((e, i) => {
      if (changed != null && !this.held[i].some((f) => changed.has(f))) return;
      const kind = e.kind ?? 'field';
      if (kind === 'field') field(e);
      else if (kind === 'date') date(e);
      else if (kind === 'phone') phone(e);
      else if (kind === 'stateZip') stateZip(e);
      else if (kind === 'notAbove') notAbove(e);
      else if (kind === 'dateOrder') dateOrder(e);
      else if (kind === 'monthYearNotPast') monthYearNotPast(e);
      else if (kind === 'anyEntered') anyEntered(e);
      else if (kind === 'differs') differs(e);
    });
    return { problems, wrongFields: this.fields.filter((f) => wrong.has(f)) };
  }

  private expand(changedFields: readonly string[]): Set<string> {
    const changed = new Set(changedFields);
    this.entries.forEach((e, i) => {
      const kind = e.kind ?? 'field';
      if ((kind === 'date' || kind === 'phone') && this.held[i].some((f) => changed.has(f))) this.held[i].forEach((f) => changed.add(f));
    });
    for (const g of this.groups) if (g.some((f) => changed.has(f))) g.forEach((f) => changed.add(f));
    return changed;
  }
}
export class RuleTable {
  private readonly forms: Map<string, FormRules>;
  private readonly lists: Record<string, string[]>;

  constructor(rules: RulesFile, lookups: LookupsFile) {
    if (rules.schema !== 1) throw new Error('rules: schema 1 expected');
    this.lists = lookups.lists;
    this.forms = new Map(Object.entries(rules.forms).map(([id, f]) => [id, new FormRules(id, f.fields, this.lists, f.groups ?? [])]));
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
