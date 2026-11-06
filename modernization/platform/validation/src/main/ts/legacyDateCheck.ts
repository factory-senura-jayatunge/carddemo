// The CSUTLDTC date check, mirrored from org.carddemo.platform.validation.LegacyDateCheck. Both are
// tested against the same vectors from the reference COBOL (src/test/resources/.../csutldtc-vectors.json).
// Self-contained on purpose: the web app copies this file as it is.

export const DASHED = 'YYYY-MM-DD';
export const COMPACT = 'YYYYMMDD';

export const INSUFFICIENT = 2507;
export const BAD_DATE_VALUE = 2508;
export const INVALID_ERA = 2509;
export const UNSUPPORTED_RANGE = 2513;
export const INVALID_MONTH = 2517;
export const BAD_PICTURE = 2518;
export const NONNUMERIC = 2520;
export const YEAR_IN_ERA_ZERO = 2521;

export interface DateCheckResult {
  /** 0 when the date is valid, else 3. */
  severity: number;
  /** The LE message number (2507 to 2521), 0 when the date is valid. */
  messageNumber: number;
  /** The verdict without its trailing spaces. */
  verdict: string;
  /** The 10 characters of the date that were tested. */
  testedDate: string;
  /** The 10 characters of the picture that were used. */
  picture: string;
  /** The date as YYYY-MM-DD when it is valid, else null. */
  value: string | null;
}

const FIELD = 10;

const VERDICTS: Record<number, string> = {
  [INSUFFICIENT]: 'Insufficient',
  [BAD_DATE_VALUE]: 'Datevalue error',
  [INVALID_ERA]: 'Invalid Era',
  [UNSUPPORTED_RANGE]: 'Unsupp. Range',
  [INVALID_MONTH]: 'Invalid month',
  [BAD_PICTURE]: 'Bad Pic String',
  [NONNUMERIC]: 'Nonnumeric data',
  [YEAR_IN_ERA_ZERO]: 'YearInEra is 0',
};

const pad = (text: string, length: number): string =>
  text.length >= length ? text.slice(0, length) : text + ' '.repeat(length - text.length);

const field = (text: string | null | undefined): string => pad(text ?? '', FIELD);

const digits = (s: string): boolean => /^[0-9]*$/.test(s);

const isAsciiLetter = (c: string): boolean => /^[A-Za-z]$/.test(c);

const asciiUpperCase = (s: string): string => s.replace(/[a-z]/g, (c) => c.toUpperCase());

/** Positions past the 10th read as spaces, as in CEEDAYS's 256-byte work area. */
const part = (date: string, from: number, length: number): string => pad(date.slice(Math.min(from, FIELD)), length);

const failed = (messageNumber: number, testedDate: string, picture: string): DateCheckResult => ({
  severity: 3,
  messageNumber,
  verdict: VERDICTS[messageNumber] ?? 'Date is invalid',
  testedDate,
  picture,
  value: null,
});

const daysInMonth = (year: number, month: number): number => new Date(Date.UTC(year, month, 0)).getUTCDate();

/**
 * Checks a date against a picture of YYYY, MM and DD plus separators, as CSUTLDTC with CEEDAYS does.
 * Both are taken as a COBOL PIC X(10): cut to 10 characters or padded with spaces.
 */
export function legacyDateCheck(date: string | null | undefined, picture: string | null | undefined): DateCheckResult {
  const d = field(date);
  const p = field(picture);
  const pic = asciiUpperCase(p);
  let yPos = -1;
  let mPos = -1;
  let dPos = -1;
  let message = 0;
  for (let i = 0; i < FIELD; i++) {
    if (yPos < 0 && pic.startsWith('YYYY', i)) {
      yPos = i;
      i += 3;
    } else if (mPos < 0 && pic.startsWith('MM', i)) {
      mPos = i;
      i += 1;
    } else if (dPos < 0 && pic.startsWith('DD', i)) {
      dPos = i;
      i += 1;
    } else if (isAsciiLetter(pic.charAt(i))) {
      message = BAD_PICTURE;
    }
  }
  if (yPos < 0 || mPos < 0 || dPos < 0) message = BAD_PICTURE;
  if (message !== 0) return failed(message, d, p);
  if (/^ *$/.test(d)) return failed(INSUFFICIENT, d, p);
  const yyyy = part(d, yPos, 4);
  const mm = part(d, mPos, 2);
  const dd = part(d, dPos, 2);
  if (!digits(yyyy) || !digits(mm) || !digits(dd)) return failed(NONNUMERIC, d, p);
  const year = Number(yyyy);
  const month = Number(mm);
  const day = Number(dd);
  if (month < 1 || month > 12) return failed(INVALID_MONTH, d, p);
  if (year < 1582 || (year === 1582 && (month < 10 || (month === 10 && day < 15)))) {
    return failed(UNSUPPORTED_RANGE, d, p);
  }
  if (day < 1 || day > daysInMonth(year, month)) return failed(BAD_DATE_VALUE, d, p);
  return { severity: 0, messageNumber: 0, verdict: 'Date is valid', testedDate: d, picture: p, value: `${yyyy}-${mm}-${dd}` };
}

/** The 80-character result line CSUTLDTC writes to its third parameter (BR-DTCK-003). */
export function resultLine(r: DateCheckResult): string {
  const four = (n: number): string => String(n).padStart(4, '0');
  return `${four(r.severity)}Mesg Code: ${four(r.messageNumber)} ${pad(r.verdict, 15)} TstDate: ${r.testedDate} Mask used:${r.picture}    `;
}
