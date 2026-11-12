import type { ReactNode } from 'react';
import { formatCurrency } from '@/lib/format';
import classes from './records.module.css';

/** One fact of a details panel: label above, value below. */
export function Fact({ label, children, wide, testId }: { label: string; children: ReactNode; wide?: boolean; testId?: string }) {
  return (
    <div className={wide ? classes.factWide : undefined}>
      <dt>{label}</dt>
      <dd data-testid={testId}>{children === '' || children === null || children === undefined ? '\u2014' : children}</dd>
    </div>
  );
}

/** An amount as currency, with the mainframe edit the service sends under it. */
export function Money({ value, display, testId }: { value: string; display: string; testId: string }) {
  return (
    <>
      <span className={classes.money}>{formatCurrency(value)}</span>
      <span className={classes.legacy} title="Mainframe format" data-testid={testId}>
        {display}
      </span>
    </>
  );
}

/** A one-letter code as the service sends it, with its meaning in words, so the tone's colour is never the only cue. */
export function Coded({ value, note, tone, testId }: { value: string; note?: string; tone?: 'good' | 'bad'; testId?: string }) {
  return (
    <span className={classes.flag} data-tone={tone}>
      <span className={classes.code} data-testid={testId}>
        {value || '\u2014'}
      </span>
      {note && <span className={classes.flagNote}>{note}</span>}
    </span>
  );
}

const FLAG_NOTE: Record<string, string> = { Y: 'Yes', N: 'No' };

/** A Y/N flag as stored, with its meaning spelled out (the stored value is what the legacy screen showed). */
export function Flag({ value, yes = 'Yes', no = 'No', testId }: { value: string; yes?: string; no?: string; testId?: string }) {
  const note = value === 'Y' ? yes : value === 'N' ? no : FLAG_NOTE[value];
  return (
    <span className={classes.flag}>
      <span className="cd-mono" data-testid={testId}>
        {value || '\u2014'}
      </span>
      {note && <span className={classes.flagNote}>{note}</span>}
    </span>
  );
}
