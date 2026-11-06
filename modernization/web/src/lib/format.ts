/**
 * Amounts arrive as decimal strings ("194.00"). The app shows them as
 * currency, with the legacy edit +9999999999.99 (NUM-BILL-001) next to it.
 */
const currency = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

function splitDecimal(amount: string): { negative: boolean; units: string; cents: string } {
  const match = /^([+-])?(\d+)(?:\.(\d{1,2}))?$/.exec(amount.trim());
  if (!match) throw new Error(`Not a decimal amount: ${amount}`);
  const [, sign, units = '0', cents = ''] = match;
  const negative = sign === '-' && /[1-9]/.test(units + cents);
  return { negative, units: units.replace(/^0+(?=\d)/, ''), cents: cents.padEnd(2, '0') };
}

/** The COBOL edit +9999999999.99: sign always shown, 10 integer digits, 2 decimals. */
export function legacyBalanceEdit(amount: string): string {
  const { negative, units, cents } = splitDecimal(amount);
  return `${negative ? '-' : '+'}${units.slice(-10).padStart(10, '0')}.${cents}`;
}

export function formatCurrency(amount: string): string {
  const { negative, units, cents } = splitDecimal(amount);
  return currency.format(Number(`${negative ? '-' : ''}${units}.${cents}`));
}

const two = (n: number) => String(n).padStart(2, '0');

/** The screen header date, mm/dd/yy (NUM-BILL-004, from the legacy header). */
export function formatHeaderDate(date: Date): string {
  return `${two(date.getMonth() + 1)}/${two(date.getDate())}/${two(date.getFullYear() % 100)}`;
}

/** The screen header time, hh:mm:ss on a 24-hour clock. */
export function formatHeaderTime(date: Date): string {
  return `${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}`;
}

/** hh:mm, for "try again after" hints. */
export function formatClockTime(date: Date): string {
  return `${two(date.getHours())}:${two(date.getMinutes())}`;
}
