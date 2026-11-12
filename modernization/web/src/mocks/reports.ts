import { DASHED, legacyDateCheck } from '@carddemo/validation/legacyDateCheck';
import type { MessageKey } from '@/messages';
import { today } from '@/validation/today';
import { maskPan } from './accounts';
import { json, problem, readBody, text } from './http';
import { sampleTransactions } from './transactionData';
import { tranAmountDisplay } from './transactions';
import { fillMessage } from './messages';

/**
 * transaction-service's report requests (RPTS) in mock mode: the checks of ReportRequestRules in
 * their order, then a run that moves on each status read: REQUESTED, RUNNING, then COMPLETED with a
 * file. Faults: report-trigger (503, the run FAILED with TRIGGER_FAILED), report-job (the run ends
 * FAILED with JOB_FAILED), report-stall (the run stays RUNNING and stalled), report-not-started (a
 * REQUESTED run reads as NOT_STARTED) and report-file (the completed run's file is missing).
 */
type ReportType = 'MONTHLY' | 'YEARLY' | 'CUSTOM';
type ReportStatus = 'REQUESTED' | 'NOT_STARTED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

interface StoredRun {
  runId: number;
  type: ReportType;
  startDate: string;
  endDate: string;
  requestedBy: string;
  requestedAt: string;
  status: ReportStatus;
  stalled: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  failureCode: string | null;
}

export interface ReportState {
  nextRunId: number;
  runs: Record<string, StoredRun>;
}

export interface ReportContext {
  request: Request;
  reports: ReportState;
  userId: string;
  role: 'ADMIN' | 'USER';
  faults: readonly string[];
}

export const initialReports = (): ReportState => ({ nextRunId: 1, runs: {} });

const LABEL: Record<ReportType, string> = { MONTHLY: 'Monthly', YEARLY: 'Yearly', CUSTOM: 'Custom' };
const PARTS = [
  ['startMonth', 'START_MONTH', 2, 1, 12],
  ['startDay', 'START_DAY', 2, 1, 31],
  ['startYear', 'START_YEAR', 4, 4, 9999],
  ['endMonth', 'END_MONTH', 2, 1, 12],
  ['endDay', 'END_DAY', 2, 1, 31],
  ['endYear', 'END_YEAR', 4, 4, 9999],
] as const;

const trim = (value: string) => value.replace(/^ +| +$/g, '');
const BIGINT_MAX = 9223372036854775807n;
const utc = () => new Date().toISOString();
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

type Range = { start: string; end: string } | { response: Response };

/** Steps 2 and 3: the type, then its range; today is the request's local date (NUM-RPTS-004). */
function range(type: ReportType, body: Record<string, unknown>): Range {
  const [y, m] = today().split('-').map(Number) as [number, number];
  if (type === 'MONTHLY') return { start: iso(new Date(y, m - 1, 1)), end: iso(new Date(y, m, 0)) };
  if (type === 'YEARLY') return { start: `${y}-01-01`, end: `${y}-12-31` };
  const values = PARTS.map(([name]) => trim(text(body[name])));
  for (const [i, [name, prefix]] of PARTS.entries()) {
    if (values[i] === '') return { response: problem(400, `${prefix}_REQUIRED` as MessageKey, name) };
  }
  const padded: string[] = [];
  for (const [i, [name, prefix, maxDigits, minDigits, max]] of PARTS.entries()) {
    const digits = values[i] ?? '';
    if (digits.length < minDigits || digits.length > maxDigits || !/^[0-9]+$/.test(digits) || Number(digits) > max) {
      return { response: problem(400, `${prefix}_INVALID` as MessageKey, name) };
    }
    padded.push(digits.padStart(maxDigits, '0'));
  }
  const [sm, sd, sy, em, ed, ey] = padded as [string, string, string, string, string, string];
  const start = `${sy}-${sm}-${sd}`;
  const end = `${ey}-${em}-${ed}`;
  if (legacyDateCheck(start, DASHED).severity !== 0) return { response: problem(400, 'START_DATE_INVALID', 'startMonth') };
  if (legacyDateCheck(end, DASHED).severity !== 0) return { response: problem(400, 'END_DATE_INVALID', 'endMonth') };
  if (start > end) return { response: problem(400, 'DATE_RANGE_INVALID', 'startMonth') };
  return { start, end };
}

const view = (run: StoredRun, message: string | null = null) => ({
  runId: run.runId,
  type: run.type,
  startDate: run.startDate,
  endDate: run.endDate,
  requestedBy: run.requestedBy,
  requestedAt: run.requestedAt,
  status: run.status,
  stalled: run.stalled,
  startedAt: run.startedAt,
  finishedAt: run.finishedAt,
  failureCode: run.failureCode,
  downloadable: run.status === 'COMPLETED',
  message,
});

async function request(ctx: ReportContext): Promise<Response> {
  if (ctx.role !== 'USER') return problem(403, 'ACCESS_DENIED', null);
  const body = await readBody(ctx.request);
  const type = body.type;
  if (type !== 'MONTHLY' && type !== 'YEARLY' && type !== 'CUSTOM') return problem(400, 'REPORT_TYPE_REQUIRED', 'monthly');
  const resolved = range(type, body);
  if ('response' in resolved) return resolved.response;
  const confirm = text(body.confirm);
  if (trim(confirm) === '') {
    return problem(422, 'CONFIRM_REQUIRED', 'confirm', { startDate: resolved.start, endDate: resolved.end }, undefined, { name: LABEL[type] });
  }
  if (confirm !== 'Y' && confirm !== 'y') {
    return problem(400, 'CONFIRM_INVALID', 'confirm', {}, undefined, { value: String.fromCodePoint(confirm.codePointAt(0) ?? 0) });
  }
  const run: StoredRun = {
    runId: ctx.reports.nextRunId,
    type,
    startDate: resolved.start,
    endDate: resolved.end,
    requestedBy: ctx.userId,
    requestedAt: utc(),
    status: 'REQUESTED',
    stalled: false,
    startedAt: null,
    finishedAt: null,
    failureCode: null,
  };
  ctx.reports.nextRunId += 1;
  ctx.reports.runs[String(run.runId)] = run;
  if (ctx.faults.includes('report-trigger')) {
    run.status = 'FAILED';
    run.failureCode = 'TRIGGER_FAILED';
    run.finishedAt = utc();
    return problem(503, 'REPORT_TRIGGER_FAILED', 'monthly');
  }
  return json(202, view(run, fillMessage('REPORT_SUBMITTED', { name: LABEL[type] })), { Location: `/api/reports/transactions/${run.runId}` });
}

/** The run of a valid ID that this user may read: the requester's own, or any for an ADMIN. */
function stored(ctx: ReportContext, typed: string): StoredRun | Response {
  const id = /^[0-9]{1,19}$/.test(typed) ? BigInt(typed) : 0n;
  if (id <= 0n || id > BIGINT_MAX) return problem(400, 'INVALID_RUN_ID', null);
  const run = ctx.reports.runs[id.toString()];
  if (!run || (run.requestedBy !== ctx.userId && ctx.role !== 'ADMIN')) return problem(404, 'REPORT_RUN_NOT_FOUND', null);
  return run;
}

/** One step of the job per status read. */
function advance(ctx: ReportContext, run: StoredRun) {
  if (run.status === 'REQUESTED') {
    run.status = 'RUNNING';
    run.startedAt = utc();
    return;
  }
  if (run.status !== 'RUNNING') return;
  if (ctx.faults.includes('report-stall')) {
    run.stalled = true;
    return;
  }
  run.finishedAt = utc();
  if (ctx.faults.includes('report-job')) {
    run.status = 'FAILED';
    run.failureCode = 'JOB_FAILED';
  } else {
    run.status = 'COMPLETED';
  }
}

/** A short stand-in for TRANREPT: the range, then the sample rows dated inside it, cards masked. */
function reportFile(run: StoredRun): string {
  const line = (s: string) => s.padEnd(133, ' ');
  const rows = sampleTransactions.filter((t) => {
    const day = t.origTs.slice(0, 10);
    return day >= run.startDate && day <= run.endDate;
  });
  return [
    line(`DALYREPT                          Daily Transaction Report                   Date Range: ${run.startDate} to ${run.endDate}`),
    line(''),
    line('Transaction ID   Card number       Type Cat  Source      Amount'),
    ...rows.map((t) => line(`${t.tranId} ${maskPan(t.cardNum)}  ${t.typeCd}   ${t.catCd} ${t.source.padEnd(10)}  ${tranAmountDisplay(t.amount)}`)),
    line(''),
    line(`Rows: ${rows.length}`),
  ].join('\n') + '\n';
}

/** Answers /api/reports/transactions and its sub-paths; null for any other path. */
export async function routeReports(method: string, path: string, ctx: ReportContext): Promise<Response | null> {
  if (path === '/api/reports/transactions') return method === 'POST' ? request(ctx) : null;
  const one = /^\/api\/reports\/transactions\/([^/]*)(\/file)?$/.exec(path);
  if (!one || method !== 'GET') return null;
  const run = stored(ctx, decodeURIComponent(one[1] ?? ''));
  if (run instanceof Response) return run;
  if (!one[2]) {
    // The service reports an old REQUESTED row as NOT_STARTED and leaves the row as it is.
    if (run.status === 'REQUESTED' && ctx.faults.includes('report-not-started')) return json(200, view({ ...run, status: 'NOT_STARTED' }));
    advance(ctx, run);
    return json(200, view(run));
  }
  if (run.status !== 'COMPLETED') return problem(409, 'REPORT_NOT_READY', null);
  if (ctx.faults.includes('report-file')) return problem(500, 'REPORT_FILE_MISSING', null);
  return new Response(reportFile(run), {
    status: 200,
    headers: {
      'Content-Type': 'text/plain; charset=US-ASCII',
      'Content-Disposition': `attachment; filename="TRANREPT-${run.runId}.txt"`,
      'Cache-Control': 'no-store',
    },
  });
}
