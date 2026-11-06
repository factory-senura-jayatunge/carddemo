import { Button, Group, Radio, Stack, Text, TextInput } from '@mantine/core';
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import { billpayApi, type PaymentResult, type Preview } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime, formatCurrency } from '@/lib/format';
import { message, uiText, type ScreenMessage } from '@/lib/messages';
import { useFieldFocus } from '@/lib/useFieldFocus';
import { newUuid } from '@/lib/uuid';
import { useUiSwitch } from '@/session/features';
import { billPayStep } from '@/validation/rules';
import classes from './BillPayPage.module.css';

type Field = 'acctId' | 'confirm';
type Confirm = '' | 'Y' | 'N';

interface Balance {
  acctId: string;
  currentBalance: string;
  currentBalanceDisplay: string;
}

function balanceFromProblem(error: ApiError, acctId: string): Balance | null {
  const { currentBalance, currentBalanceDisplay } = error.body;
  return typeof currentBalance === 'string' && typeof currentBalanceDisplay === 'string'
    ? { acctId, currentBalance, currentBalanceDisplay }
    : null;
}

async function pay(acctId: string, key: string): Promise<PaymentResult> {
  const call = () =>
    unwrap(
      billpayApi.POST('/api/billpay/accounts/{acctId}/payments', {
        params: { path: { acctId }, header: { 'Idempotency-Key': key } },
        body: { confirm: 'Y' },
      }),
    );
  try {
    return await call();
  } catch (error) {
    // The same key on a retry after a lost connection: the service answers
    // with the stored result if the first request was paid.
    if (error instanceof ApiError && error.isNetwork) return call();
    throw error;
  }
}

function LockIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <rect x="3.25" y="7" width="9.5" height="6.5" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M5.5 7V5.2a2.5 2.5 0 015 0V7" fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

function BalancePanel({ balance }: { balance: Balance | null }) {
  if (!balance) {
    return (
      <div className={classes.panel} data-empty>
        <Text fw={600}>Current balance</Text>
        <Text size="sm" c="dimmed" mt={6}>
          Enter an account ID and check its balance. The payment is always the full balance.
        </Text>
      </div>
    );
  }
  return (
    <div className={classes.panel} data-testid="balance-panel">
      <Text size="sm" c="dimmed" fw={560}>
        Current balance
      </Text>
      <p className={classes.amount} data-testid="balance-amount">
        {formatCurrency(balance.currentBalance)}
      </p>
      <dl className={classes.facts}>
        <div>
          <dt>Account</dt>
          <dd className="cd-mono">{balance.acctId}</dd>
        </div>
        <div>
          <dt>Mainframe format</dt>
          <dd className="cd-mono" data-testid="balance-display">
            {balance.currentBalanceDisplay}
          </dd>
        </div>
      </dl>
    </div>
  );
}

/** COBIL00C as a web page: pay the full balance of an account. */
export function BillPayPage() {
  const navigate = useNavigate();
  const lockAfterPreview = useUiSwitch('DEF-BILL-014') === 'fixed';
  const [acctId, setAcctId] = useState('');
  const [confirm, setConfirm] = useState<Confirm>('');
  const [balance, setBalance] = useState<Balance | null>(null);
  const [payable, setPayable] = useState(false);
  const [locked, setLocked] = useState(false);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [errorField, setErrorField] = useState<Field | null>(null);
  const [receipt, setReceipt] = useState<PaymentResult | null>(null);
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const acctRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLInputElement>(null);
  const focus = useFieldFocus<Field>({ acctId: acctRef, confirm: confirmRef }, 'acctId');

  const show = (next: ScreenMessage | null, field: Field | null) => {
    setScreenMessage(next);
    setErrorField(next?.tone === 'error' ? field : null);
    if (field) focus(field);
  };

  const clearAll = () => {
    setAcctId('');
    setConfirm('');
    setBalance(null);
    setPayable(false);
    setLocked(false);
    setReceipt(null);
    setRetryAt(null);
    show(null, 'acctId');
  };

  const showError = (error: unknown) => {
    if (!(error instanceof ApiError)) {
      show({ text: uiText.noConnection, tone: 'error' }, 'acctId');
      return;
    }
    const field: Field = error.field === 'confirm' ? 'confirm' : 'acctId';
    if (error.code === 'BATCH_WINDOW') {
      setRetryAt(new Date(Date.now() + (error.retryAfterSeconds ?? 300) * 1000));
      show({ text: error.message, tone: 'notice' }, null);
      return;
    }
    if (error.code === 'NOTHING_TO_PAY') {
      setBalance(balanceFromProblem(error, acctId));
      setPayable(false);
    }
    if (error.code === 'ACCOUNT_NOT_FOUND' || error.code === 'ACCT_ID_REQUIRED') {
      setBalance(null);
      setPayable(false);
      setLocked(false);
    }
    show({ text: error.isNetwork ? uiText.noConnection : error.message, tone: error.severity }, field);
  };

  const preview = async () => {
    const result: Preview = await unwrap(
      billpayApi.GET('/api/billpay/accounts/{acctId}', { params: { path: { acctId } } }),
    );
    setBalance({ acctId: result.acctId, currentBalance: result.currentBalance, currentBalanceDisplay: result.currentBalanceDisplay });
    setPayable(result.payable);
    setLocked(lockAfterPreview && result.payable);
    show({ text: result.message, tone: result.severity }, result.field === 'acctId' ? 'acctId' : 'confirm');
  };

  const payNow = async () => {
    const result = await pay(acctId, newUuid());
    setAcctId('');
    setConfirm('');
    setBalance(null);
    setPayable(false);
    setLocked(false);
    setReceipt(result);
    show({ text: result.message, tone: result.severity }, 'acctId');
  };

  const enter = async (event?: FormEvent) => {
    event?.preventDefault();
    if (busy) return;
    setReceipt(null);
    setRetryAt(null);
    const step = billPayStep(acctId, confirm);
    if (step.kind === 'problem') {
      show(message(step.problem.code), step.problem.field);
      return;
    }
    if (step.kind === 'clear') {
      clearAll();
      return;
    }
    setBusy(true);
    try {
      await (step.kind === 'preview' ? preview() : payNow());
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };

  const back = () => void navigate('/');
  usePageKeys({ back, clear: clearAll, invalidKey: () => show(message('INVALID_KEY'), null) });

  const onAcctChange = (value: string) => {
    setAcctId(value);
    // A balance on screen belongs to the account it was read for.
    if (balance) {
      setBalance(null);
      setPayable(false);
      setScreenMessage(null);
      setErrorField(null);
    }
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

  const submitLabel =
    confirm === 'Y'
      ? balance && payable
        ? `Pay ${formatCurrency(balance.currentBalance)}`
        : 'Pay the full balance'
      : confirm === 'N'
        ? 'Start over'
        : balance
          ? 'Check again'
          : 'Check balance';

  const messageId = 'bill-pay-message';
  const describedBy = (field: Field, ...extra: string[]) =>
    [...extra, errorField === field ? messageId : null].filter(Boolean).join(' ') || undefined;

  return (
    <>
      <PageTitle
        title="Bill payment"
        description="Pay the full current balance of an account."
        actions={
          <Button variant="default" onClick={back}>
            Back to menu
            <KeyHint name="F3" />
          </Button>
        }
      />
      <div className={classes.layout}>
        <form ref={form} onSubmit={(event) => void enter(event)} noValidate className={classes.card} aria-describedby={messageId}>
          <Stack gap="lg">
            <TextInput
              ref={acctRef}
              label="Account ID"
              value={acctId}
              onChange={(event) => onAcctChange(event.currentTarget.value)}
              maxLength={11}
              inputMode="numeric"
              autoComplete="off"
              spellCheck={false}
              readOnly={locked}
              description={
                <span id="acct-hint">
                  {locked ? 'Locked while you confirm this payment. Choose Clear to pay another account.' : '11 digits, for example 00000000001.'}
                </span>
              }
              rightSection={locked ? <LockIcon /> : undefined}
              error={errorField === 'acctId' ? true : undefined}
              aria-describedby={describedBy('acctId', 'acct-hint')}
              classNames={{ wrapper: classes.acctWrapper, input: classes.acctInput }}
              data-testid="acct-id"
              data-locked={locked || undefined}
            />
            <Radio.Group
              value={confirm}
              onChange={(value) => setConfirm(value === 'Y' || value === 'N' ? value : '')}
              label="Pay the full balance now?"
              description={
                <span id="confirm-hint">
                  {balance ? 'Yes pays the balance shown. No clears the form.' : 'Leave both unselected to check the balance first.'}
                </span>
              }
              error={errorField === 'confirm' ? true : undefined}
              aria-describedby={describedBy('confirm', 'confirm-hint')}
              onKeyDown={onConfirmKey}
              data-testid="confirm"
            >
              <Group gap="xl" mt={10}>
                <Radio ref={confirmRef} value="Y" label="Yes" data-testid="confirm-yes" />
                <Radio value="N" label="No" data-testid="confirm-no" />
              </Group>
            </Radio.Group>
            <MessageArea id={messageId} message={screenMessage}>
              {receipt && (
                <Text size="sm" mt={4} c="inherit">
                  {formatCurrency(receipt.amount)} paid on account <span className="cd-mono">{receipt.acctId}</span>.
                </Text>
              )}
              {retryAt && (
                <Group gap="sm" mt={8} align="center">
                  <Text size="sm" c="inherit">
                    Try again after {formatClockTime(retryAt)}.
                  </Text>
                  <Button size="compact-sm" variant="white" color="dark" onClick={() => form.current?.requestSubmit()}>
                    Try again
                  </Button>
                </Group>
              )}
            </MessageArea>
            <Group gap="sm" justify="space-between" className={classes.actions}>
              <Button variant="subtle" color="slate" onClick={clearAll} disabled={busy}>
                Clear
                <KeyHint name="F4" />
              </Button>
              <Button type="submit" loading={busy} data-testid="submit">
                {submitLabel}
              </Button>
            </Group>
          </Stack>
        </form>
        <BalancePanel balance={balance} />
      </div>
    </>
  );
}
