import { Badge, Button, Group, Radio, Text, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import { transactionApi, type ReportRequest, type ReportRun } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { catalogue, uiText, type ScreenMessage } from '@/lib/messages';
import { reportMessages } from '@/messages/report';
import { useIdFocus } from '@/lib/useIdFocus';
import { problemMessage } from '@/lib/useReveal';
import { Fact } from './RecordParts';
import css from './ReportRequestPage.module.css';
import classes from './records.module.css';

const { message } = catalogue(reportMessages);

type ReportType = ReportRun['type'];
type Confirm = '' | 'Y' | 'N';

const START = [
  { name: 'startMonth', label: 'Month', hint: 'MM' },
  { name: 'startDay', label: 'Day', hint: 'DD' },
  { name: 'startYear', label: 'Year', hint: 'YYYY' },
] as const;
const END = [
  { name: 'endMonth', label: 'Month', hint: 'MM' },
  { name: 'endDay', label: 'Day', hint: 'DD' },
  { name: 'endYear', label: 'Year', hint: 'YYYY' },
] as const;
type Part = (typeof START)[number]['name'] | (typeof END)[number]['name'];
type Parts = Record<Part, string>;
type Field = 'monthly' | Part | 'confirm';

const PART_NAMES = [...START, ...END].map((part) => part.name);
const FIELDS: Field[] = ['monthly', ...PART_NAMES, 'confirm'];
const EMPTY_PARTS = Object.fromEntries(PART_NAMES.map((name) => [name, ''])) as Parts;
const isField = (name: string | null): name is Field => name !== null && (FIELDS as string[]).includes(name);
const fieldId = (field: Field) => `report-${field}`;

const TYPES: { value: ReportType; label: string; description: string }[] = [
  { value: 'MONTHLY', label: 'Monthly', description: 'From the first to the last day of the current month.' },
  { value: 'YEARLY', label: 'Yearly', description: 'From 1 January to 31 December of the current year.' },
  { value: 'CUSTOM', label: 'Custom', description: 'From a start date to an end date that you choose.' },
];
const NAME: Record<ReportType, string> = { MONTHLY: 'Monthly', YEARLY: 'Yearly', CUSTOM: 'Custom' };

const STATUS: Record<ReportRun['status'], { label: string; color: string; sentence: string }> = {
  REQUESTED: { label: 'Requested', color: 'slate', sentence: 'Waiting for the report job to start.' },
  NOT_STARTED: { label: 'Not started', color: 'orange', sentence: 'The report job has not started in the expected time. It may still start later; this page no longer checks it.' },
  RUNNING: { label: 'Running', color: 'blue', sentence: 'The report is running.' },
  COMPLETED: { label: 'Ready', color: 'green', sentence: 'The report is ready to download.' },
  FAILED: { label: 'Failed', color: 'red', sentence: 'The report did not finish.' },
};

/** The status is read every 5 seconds while the run is REQUESTED or RUNNING. */
export const POLL_MS = 5000;

/** The states the run never leaves. A stalled run is still RUNNING and may recover, so it is still read. */
const finished = (run: ReportRun) => run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'NOT_STARTED';

const fileNameOf = (disposition: string | null | undefined) => /filename="?([^";]+)"?/i.exec(disposition ?? '')?.[1];

const statusCall = (runId: number) =>
  unwrap(transactionApi.GET('/api/reports/transactions/{runId}', { params: { path: { runId: String(runId) } } }));

function DownloadIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path d="M8 2.5v7.5M4.75 7 8 10.25 11.25 7M3 13.25h10" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const STEPS = ['Requested', 'Running', 'Ready'] as const;

function stepOf(run: ReportRun): number {
  if (run.status === 'COMPLETED') return 3;
  if (run.status === 'RUNNING') return 1;
  return run.status === 'REQUESTED' ? 0 : -1;
}

function RunPanel({ run, runMessage, onDownload, downloading }: { run: ReportRun; runMessage: ScreenMessage | null; onDownload: () => void; downloading: boolean }) {
  const status = STATUS[run.status];
  const step = stepOf(run);
  const sentence = run.stalled
    ? 'The report job stopped responding.'
    : run.status === 'FAILED' && run.failureCode
      ? `${status.sentence} Reason: ${run.failureCode}.`
      : status.sentence;
  return (
    <section className={`${classes.panel} ${css.aside}`} aria-labelledby="run-title" data-testid="run-panel">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>
            Report run <span data-testid="run-id">{run.runId}</span>
          </p>
          <h2 id="run-title" className={classes.panelTitle}>
            {NAME[run.type]} report
          </h2>
        </div>
        <Badge color={run.stalled ? 'red' : status.color} variant="light" radius="sm" data-testid="run-status">
          {run.stalled ? 'Stalled' : status.label}
        </Badge>
      </div>
      {step >= 0 && (
        <ol className={css.steps} aria-label="Progress">
          {STEPS.map((label, index) => (
            <li key={label} data-state={index < step ? 'done' : index === step ? 'current' : 'todo'} aria-current={index === step ? 'step' : undefined}>
              {label}
            </li>
          ))}
        </ol>
      )}
      <p className={css.statusLine} aria-live="polite" data-testid="run-status-text">
        {sentence}
      </p>
      <dl className={classes.facts}>
        <Fact label="Period" testId="run-period">
          <span className="cd-mono">
            {run.startDate} to {run.endDate}
          </span>
        </Fact>
        <Fact label="Requested at" testId="run-requested">
          {formatClockTime(new Date(run.requestedAt))}
        </Fact>
      </dl>
      {run.downloadable && (
        <Button mt="lg" leftSection={<DownloadIcon />} onClick={onDownload} loading={downloading} data-testid="download">
          Download report
        </Button>
      )}
      <div className={classes.messageSlot}>
        <MessageArea id="report-run-message" message={runMessage} />
      </div>
    </section>
  );
}

function EmptyRun() {
  return (
    <section className={`${classes.panel} ${css.aside}`} data-empty aria-labelledby="run-title" data-testid="run-empty">
      <h2 id="run-title" className={classes.panelTitle}>
        Report status
      </h2>
      <Text size="sm" c="dimmed" mt={6}>
        After you confirm, the report runs in the background. Its progress shows here, with a download link when it is ready.
      </Text>
    </section>
  );
}

/**
 * CORPT00C as a web page: choose the period, confirm, and follow the run until its file can be
 * downloaded. The date parts go to the service as typed; it pads and checks them.
 */
export function ReportRequestPage() {
  const navigate = useNavigate();
  const unexpected = useUnexpected();
  const [type, setType] = useState<ReportType | ''>('');
  const [parts, setParts] = useState<Parts>(EMPTY_PARTS);
  const [confirm, setConfirm] = useState<Confirm>('');
  const [range, setRange] = useState<{ start: string; end: string } | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [errorField, setErrorField] = useState<Field | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [run, setRun] = useState<ReportRun | null>(null);
  const [runMessage, setRunMessage] = useState<ScreenMessage | null>(null);
  const [downloading, setDownloading] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const focus = useIdFocus<Field>(fieldId, 'monthly');

  const show = (next: ScreenMessage | null, field: Field | null) => {
    setScreenMessage(next);
    setErrorField(next?.tone === 'error' ? field : null);
    if (field) focus(field);
  };

  /** Clears the selection, the dates, the confirm field and the message (FLOW-RPTS-008). */
  const clearForm = () => {
    setType('');
    setParts(EMPTY_PARTS);
    setConfirm('');
    setRange(null);
  };

  const runId = run?.runId;
  const active = run !== null && !finished(run);
  useEffect(() => {
    if (runId === undefined || !active) return;
    let cancelled = false;
    let timer: number | undefined;
    const stalledText = message('REPORT_RUN_STALLED');
    const tick = async () => {
      try {
        const next = await statusCall(runId);
        if (cancelled) return;
        setRun(next);
        setRunMessage((shown) => (next.stalled ? stalledText : shown?.text === stalledText.text ? null : shown));
        if (finished(next)) return;
      } catch (error) {
        if (!cancelled && !unexpected(error)) setRunMessage(problemMessage(error));
        return;
      }
      timer = window.setTimeout(() => void tick(), POLL_MS);
    };
    timer = window.setTimeout(() => void tick(), POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [runId, active, unexpected]);

  const body = (confirmValue: string): ReportRequest => ({
    type: type === '' ? null : type,
    ...(type === 'CUSTOM' ? parts : {}),
    confirm: confirmValue,
  });

  const showError = (error: unknown) => {
    if (unexpected(error)) return;
    if (!(error instanceof ApiError) || error.isNetwork) {
      show({ text: uiText.noConnection, tone: 'error' }, 'monthly');
      return;
    }
    const { startDate, endDate } = error.body;
    if (error.code === 'CONFIRM_REQUIRED' && typeof startDate === 'string' && typeof endDate === 'string') setRange({ start: startDate, end: endDate });
    else setRange(null);
    show({ text: error.message, tone: error.severity }, isField(error.field) ? error.field : 'monthly');
  };

  const request = async (confirmValue: string) => {
    const result = await unwrap(transactionApi.POST('/api/reports/transactions', { body: body(confirmValue) }));
    clearForm();
    setRun(result);
    setRunMessage(null);
    show(result.message ? { text: result.message, tone: 'success' } : null, 'monthly');
  };

  /** No: the same checks as a blank confirm, and when they pass the form is cleared with no message. */
  const decline = async () => {
    try {
      await unwrap(transactionApi.POST('/api/reports/transactions', { body: body('') }));
    } catch (error) {
      if (error instanceof ApiError && error.code === 'CONFIRM_REQUIRED') {
        clearForm();
        show(null, 'monthly');
        return;
      }
      throw error;
    }
  };

  const enter = (event?: FormEvent) => {
    event?.preventDefault();
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    (confirm === 'N' ? decline() : request(confirm))
      .catch(showError)
      .finally(() => {
        busyRef.current = false;
        setBusy(false);
      });
  };

  const download = async () => {
    if (!run) return;
    setDownloading(true);
    setRunMessage(null);
    try {
      let disposition: string | null = null;
      const file = await unwrap(
        transactionApi
          .GET('/api/reports/transactions/{runId}/file', { params: { path: { runId: String(run.runId) } }, parseAs: 'blob' })
          .then((result) => {
            disposition = result.response.headers.get('Content-Disposition');
            return result;
          }),
      );
      const url = URL.createObjectURL(file);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileNameOf(disposition) ?? `TRANREPT-${run.runId}.txt`;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      if (!unexpected(error)) setRunMessage(problemMessage(error));
    } finally {
      setDownloading(false);
    }
  };

  // FLOW-RPTS-004: back to the main menu, whatever page opened this one.
  const back = () => void navigate('/');
  usePageKeys({ back, invalidKey: () => show(message('INVALID_KEY'), 'monthly') });

  const choose = (value: string) => {
    setType(value === 'MONTHLY' || value === 'YEARLY' || value === 'CUSTOM' ? value : '');
    setRange(null);
  };

  const onPart = (name: Part, value: string) => {
    setParts((current) => ({ ...current, [name]: value }));
    setRange(null);
  };

  const onConfirmKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const key = event.key.toUpperCase();
    if (key === 'Y' || key === 'N') {
      event.preventDefault();
      setConfirm(key);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      form.current?.requestSubmit();
    }
  };

  const messageId = 'report-message';
  const describedBy = (field: Field, ...extra: string[]) => [...extra, errorField === field ? messageId : null].filter(Boolean).join(' ') || undefined;

  const dateGroup = (legend: string, group: typeof START | typeof END) => (
    <fieldset className={css.dateGroup}>
      <legend className={css.dateLegend}>{legend}</legend>
      <div className={css.dateParts}>
        {group.map((part) => (
          <TextInput
            key={part.name}
            id={fieldId(part.name)}
            label={part.label}
            placeholder={part.hint}
            value={parts[part.name]}
            onChange={(event) => onPart(part.name, event.currentTarget.value)}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            error={errorField === part.name ? true : undefined}
            aria-describedby={describedBy(part.name)}
            className={part.hint === 'YYYY' ? css.year : css.part}
            classNames={{ input: classes.keyInput }}
            data-testid={part.name}
          />
        ))}
      </div>
    </fieldset>
  );

  const submitLabel = confirm === 'Y' ? 'Print report' : confirm === 'N' ? 'Start over' : range ? 'Check again' : 'Continue';

  return (
    <>
      <PageTitle
        title="Transaction report"
        description="Print the transactions processed in a month, a year or a range of dates."
        actions={
          <Button variant="default" onClick={back} data-testid="back">
            Back to menu
            <KeyHint name="F3" />
          </Button>
        }
      />
      <div className={css.layout}>
        <form ref={form} onSubmit={enter} noValidate className={css.card} aria-describedby={messageId} aria-label="Report request">
          <Radio.Group
            value={type}
            onChange={choose}
            label="Report period"
            error={errorField === 'monthly' ? true : undefined}
            aria-describedby={describedBy('monthly')}
            data-testid="report-type"
          >
            <div className={css.types}>
              {TYPES.map((option) => (
                <Radio
                  key={option.value}
                  id={option.value === 'MONTHLY' ? fieldId('monthly') : undefined}
                  value={option.value}
                  label={option.label}
                  description={option.description}
                  classNames={{ root: css.typeCard, label: css.typeLabel }}
                  data-testid={`type-${option.value.toLowerCase()}`}
                />
              ))}
            </div>
          </Radio.Group>
          {type === 'CUSTOM' && (
            <div className={css.dates} data-testid="custom-dates">
              {dateGroup('Start date', START)}
              {dateGroup('End date', END)}
            </div>
          )}
          <div className={classes.confirmBar}>
            <Radio.Group
              value={confirm}
              onChange={(value) => setConfirm(value === 'Y' || value === 'N' ? value : '')}
              label="Print this report now?"
              description={
                <span id="report-confirm-hint" data-testid="report-range">
                  {range ? `It covers the transactions processed from ${range.start} to ${range.end}.` : 'Leave both unselected to see the dates first.'}
                </span>
              }
              aria-describedby={describedBy('confirm', 'report-confirm-hint')}
              onKeyDown={onConfirmKey}
              data-testid="confirm"
            >
              <Group gap="xl" mt={10}>
                <Radio id={fieldId('confirm')} value="Y" label="Yes" data-testid="confirm-yes" />
                <Radio value="N" label="No" data-testid="confirm-no" />
              </Group>
            </Radio.Group>
          </div>
          <div className={classes.messageSlot}>
            <MessageArea id={messageId} message={screenMessage} />
          </div>
          <div className={classes.formActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              {submitLabel}
            </Button>
          </div>
        </form>
        {run ? <RunPanel run={run} runMessage={runMessage} onDownload={() => void download()} downloading={downloading} /> : <EmptyRun />}
      </div>
    </>
  );
}
