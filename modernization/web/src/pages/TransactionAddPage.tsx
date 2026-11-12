import { Button, Group, Radio, Text, TextInput } from '@mantine/core';
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { transactionApi, type AddResult, type TransactionCheck, type TransactionForm } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime, formatCurrency } from '@/lib/format';
import { catalogue, uiText, type ScreenMessage } from '@/lib/messages';
import { transactionMessages } from '@/messages/transaction';
import { useBack } from '@/lib/useBack';
import { useIdFocus } from '@/lib/useIdFocus';
import { newUuid } from '@/lib/uuid';
import { today } from '@/validation/today';
import { Fact } from './RecordParts';
import css from './TransactionAddPage.module.css';
import classes from './records.module.css';

const { message } = catalogue(transactionMessages);

type Confirm = '' | 'Y' | 'N';

interface InputSpec {
  name: string;
  label: string;
  hint: string;
  span: 2 | 3 | 4 | 6;
  digits?: boolean;
}

const KEY_INPUTS = [
  { name: 'acctId', label: 'Account ID', hint: 'Up to 11 digits; leading zeros are optional.', span: 3, digits: true },
  { name: 'cardNum', label: 'Card number', hint: 'Up to 16 digits.', span: 3, digits: true },
] as const satisfies readonly InputSpec[];

const DETAIL_INPUTS = [
  { name: 'typeCd', label: 'Type code', hint: 'Up to 2 digits, for example 01.', span: 2, digits: true },
  { name: 'catCd', label: 'Category code', hint: 'Up to 4 digits, for example 0001.', span: 2, digits: true },
  { name: 'source', label: 'Source', hint: 'Up to 10 characters, for example POS TERM.', span: 2 },
  { name: 'description', label: 'Description', hint: 'Up to 60 characters.', span: 6 },
  { name: 'amount', label: 'Amount', hint: 'A sign, 8 digits and 2 decimals: -00000012.50 or +00000012.50.', span: 3, digits: true },
  { name: 'origDate', label: 'Original date', hint: 'YYYY-MM-DD, for example 2022-07-06.', span: 3, digits: true },
] as const satisfies readonly InputSpec[];

const MERCHANT_INPUTS = [
  { name: 'merchantId', label: 'Merchant ID', hint: 'Up to 9 digits.', span: 2, digits: true },
  { name: 'merchantName', label: 'Merchant name', hint: 'Up to 30 characters.', span: 4 },
  { name: 'merchantCity', label: 'Merchant city', hint: 'Up to 25 characters.', span: 3 },
  { name: 'merchantZip', label: 'Merchant ZIP code', hint: 'Up to 10 characters.', span: 3 },
] as const satisfies readonly InputSpec[];

type KeyField = (typeof KEY_INPUTS)[number]['name'];
type InputField = KeyField | (typeof DETAIL_INPUTS)[number]['name'] | (typeof MERCHANT_INPUTS)[number]['name'];
type Field = InputField | 'confirm';
type Values = Record<InputField, string>;

const INPUTS: readonly InputSpec[] = [...KEY_INPUTS, ...DETAIL_INPUTS, ...MERCHANT_INPUTS];
const FIELDS = [...INPUTS.map((input) => input.name), 'confirm'] as Field[];
const EMPTY = Object.fromEntries(INPUTS.map((input) => [input.name, ''])) as Values;
const SPAN = { 2: css.span2, 3: css.span3, 4: css.span4, 6: css.span6 } as const;
const isField = (name: string | null): name is Field => name !== null && (FIELDS as string[]).includes(name);
const fieldId = (field: Field) => `tran-add-${field}`;
const SPACES = /^ *$/;

/** What the service resolved from the key fields: the account padded, the card masked. */
interface Resolved {
  acctId: string;
  cardNum: string;
}

const resolvedOf = (body: Record<string, unknown>): Resolved | null =>
  typeof body.acctId === 'string' && typeof body.cardNum === 'string' ? { acctId: body.acctId, cardNum: body.cardNum } : null;

const validateCall = (form: Values) => unwrap(transactionApi.POST('/api/transactions/validate', { body: form }));

async function addCall(body: TransactionForm, key: string): Promise<AddResult> {
  const call = () => unwrap(transactionApi.POST('/api/transactions', { params: { header: { 'Idempotency-Key': key } }, body }));
  try {
    return await call();
  } catch (error) {
    // The same key on a retry after a lost connection: the service answers
    // with the stored result if the first request was added.
    if (error instanceof ApiError && error.isNetwork) return call();
    throw error;
  }
}

/** A failure of the account and card checks, before any data field was looked at. */
const failedAtKeys = (error: unknown) => !(error instanceof ApiError) || error.isNetwork || typeof error.body.acctId !== 'string';

function ReviewPanel({ review, values }: { review: TransactionCheck | null; values: Values }) {
  if (!review) {
    return (
      <section className={`${classes.panel} ${css.aside}`} data-empty aria-labelledby="review-title" data-testid="review-empty">
        <h2 id="review-title" className={classes.panelTitle}>
          Review
        </h2>
        <Text size="sm" c="dimmed" mt={6}>
          Fill in the form and choose Review to see the account, the card and the amount. Nothing is saved until you confirm.
        </Text>
      </section>
    );
  }
  return (
    <section className={`${classes.panel} ${css.aside}`} aria-labelledby="review-title" data-testid="review-panel">
      <p className={classes.panelKicker}>Review</p>
      <h2 id="review-title" className={classes.panelTitle}>
        {values.description || 'New transaction'}
      </h2>
      <div className={classes.balance}>
        <p className={classes.balanceAmount}>{formatCurrency(review.amount)}</p>
        <span className={classes.legacy} title="Mainframe format" data-testid="review-amount">
          {review.amountDisplay}
        </span>
      </div>
      <dl className={classes.facts}>
        <Fact label="Account" testId="review-acct">
          <span className="cd-mono">{review.acctId}</span>
        </Fact>
        <Fact label="Card" testId="review-card">
          <span className="cd-mono">{review.cardNum}</span>
        </Fact>
        <Fact label="Type and category" testId="review-codes">
          <span className="cd-mono">
            {review.typeCd} / {review.catCd}
          </span>
        </Fact>
        <Fact label="Merchant ID" testId="review-merchant">
          <span className="cd-mono">{review.merchantId}</span>
        </Fact>
        <Fact label="Original date">
          <span className="cd-mono">{values.origDate}</span>
        </Fact>
        <Fact label="Processing date" testId="review-proc">
          <span className="cd-mono">{review.procDate}</span>
        </Fact>
      </dl>
    </section>
  );
}

/**
 * COTRN02C as a web page: validate, confirm Yes, and the transaction waits for the next batch
 * cycle (BR-TRNA-007). Values go to the service as typed; it pads and checks them.
 */
export function TransactionAddPage() {
  const back = useBack('/');
  const unexpected = useUnexpected();
  const [values, setValues] = useState<Values>(EMPTY);
  const [confirm, setConfirm] = useState<Confirm>('');
  const [review, setReview] = useState<TransactionCheck | null>(null);
  // The processing date is the service's today in its own zone; until a service answer gives it,
  // the page can only show this device's date, labelled as such.
  const [serviceDate, setServiceDate] = useState<string | null>(null);
  const [resolved, setResolved] = useState<Resolved | null>(null);
  const [cards, setCards] = useState<string[] | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const [errorField, setErrorField] = useState<Field | null>(null);
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  // One key per confirmed body: a retry of the same add replays, any edit makes a new add.
  const keyRef = useRef<{ body: string; key: string } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const focus = useIdFocus<Field>(fieldId, 'acctId');

  const show = (next: ScreenMessage | null, field: Field | null) => {
    setScreenMessage(next);
    setHint(null);
    setErrorField(next?.tone === 'error' ? field : null);
    if (field) focus(field);
  };

  const clearAll = () => {
    setValues(EMPTY);
    setConfirm('');
    setReview(null);
    setServiceDate(null);
    setResolved(null);
    setCards(null);
    setRetryAt(null);
    keyRef.current = null;
    show(null, 'acctId');
  };

  const showError = (error: unknown) => {
    if (unexpected(error)) return;
    if (!(error instanceof ApiError) || error.isNetwork) {
      show({ text: uiText.noConnection, tone: 'error' }, 'acctId');
      return;
    }
    if (error.code === 'BATCH_WINDOW') {
      setRetryAt(new Date(Date.now() + (error.retryAfterSeconds ?? 300) * 1000));
      show({ text: error.message, tone: 'notice' }, null);
      return;
    }
    setReview(null);
    setResolved(resolvedOf(error.body));
    const listed = error.body.cards;
    setCards(Array.isArray(listed) ? listed.filter((card): card is string => typeof card === 'string') : null);
    show({ text: error.message, tone: error.severity }, isField(error.field) ? error.field : 'acctId');
  };

  const check = async (form: Values) => {
    const result = await validateCall(form);
    setReview(result);
    setServiceDate(result.procDate);
    setResolved({ acctId: result.acctId, cardNum: result.cardNum });
    setCards(null);
    show({ text: result.message, tone: result.severity }, 'confirm');
  };

  const addNow = async (form: Values) => {
    const body: TransactionForm = { ...form, confirm: 'Y' };
    const signature = JSON.stringify(body);
    if (keyRef.current?.body !== signature) keyRef.current = { body: signature, key: newUuid() };
    const result = await addCall(body, keyRef.current.key);
    keyRef.current = null;
    setValues(EMPTY);
    setConfirm('');
    setReview(null);
    setServiceDate(null);
    setResolved(null);
    setCards(null);
    show({ text: result.message, tone: result.severity }, 'acctId');
    setHint(result.hint.message);
  };

  /** Both keys empty (spaces only, as the legacy check): the service's first rule, shown without a call. */
  const keysMissing = (form: Values) => {
    if (!SPACES.test(form.acctId) || !SPACES.test(form.cardNum)) return false;
    setReview(null);
    setResolved(null);
    setCards(null);
    show(message('KEY_REQUIRED'), 'acctId');
    return true;
  };

  /**
   * The Enter logic, in Bill Pay's order (BR-TRNA-002): the key presence first; then No cancels the
   * add with no call (DEF-TRNA-014 fixed per D-75), Yes adds, and nothing validates and asks to confirm.
   */
  const enterWith = async (form: Values) => {
    if (keysMissing(form)) return;
    if (confirm === 'N') clearAll();
    else if (confirm === 'Y') await addNow(form);
    else await check(form);
  };

  const guarded = async (work: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setRetryAt(null);
    try {
      await work();
    } catch (error) {
      showError(error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const enter = (event?: FormEvent) => {
    event?.preventDefault();
    void guarded(() => enterWith(values));
  };

  /** PF5 (BR-TRNA-006): the key checks first, then the last transaction's data fields, then Enter. */
  const copyLast = () =>
    void guarded(async () => {
      if (keysMissing(values)) return;
      try {
        await validateCall(values);
      } catch (error) {
        if (failedAtKeys(error)) throw error;
      }
      const last = await unwrap(transactionApi.GET('/api/transactions/latest'));
      setServiceDate(last.procDate);
      const next: Values = {
        ...values,
        typeCd: last.typeCd,
        catCd: last.catCd,
        source: last.source,
        description: last.description,
        amount: last.amountDisplay,
        origDate: last.origDate,
        merchantId: last.merchantId,
        merchantName: last.merchantName,
        merchantCity: last.merchantCity,
        merchantZip: last.merchantZip,
      };
      setValues(next);
      setReview(null);
      await enterWith(next);
    });

  const invalidKey = () => {
    show(message('INVALID_KEY'), null);
    focus('acctId');
  };
  usePageKeys({ back, clear: clearAll, save: copyLast, invalidKey });

  const onChange = (field: InputField, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    if (field === 'acctId' || field === 'cardNum') {
      setResolved(null);
      setCards(null);
    }
    // A review on screen belongs to the values it was made for.
    if (review) {
      setReview(null);
      setResolved(null);
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

  const messageId = 'tran-add-message';
  const describedBy = (field: Field, ...extra: string[]) => [...extra, errorField === field ? messageId : null].filter(Boolean).join(' ') || undefined;

  const input = (spec: InputSpec) => {
    const name = spec.name as InputField;
    const hintId = `${name}-hint`;
    return (
      <TextInput
        key={name}
        id={fieldId(name)}
        label={spec.label}
        value={values[name]}
        onChange={(event) => onChange(name, event.currentTarget.value)}
        autoComplete="off"
        spellCheck={false}
        inputMode={spec.digits && name !== 'amount' && name !== 'origDate' ? 'numeric' : undefined}
        description={<span id={hintId}>{spec.hint}</span>}
        error={errorField === name ? true : undefined}
        aria-describedby={describedBy(name, hintId)}
        className={SPAN[spec.span]}
        classNames={spec.digits ? { input: classes.keyInput } : undefined}
        data-testid={name}
      />
    );
  };

  const submitLabel = confirm === 'Y' ? 'Add transaction' : review ? 'Check again' : 'Review';

  return (
    <>
      <PageTitle
        title="Add a transaction"
        description="Record a transaction on a card. It is posted to the account in the next batch cycle."
        actions={
          <>
            <Button variant="default" onClick={copyLast} disabled={busy} data-testid="copy-last">
              Copy last transaction
              <KeyHint name="F5" />
            </Button>
            <Button variant="default" onClick={back} data-testid="back">
              Back
              <KeyHint name="F3" />
            </Button>
          </>
        }
      />
      <div className={css.layout}>
        <form ref={form} onSubmit={enter} noValidate className={css.card} aria-describedby={messageId} aria-label="New transaction">
          <fieldset className={css.section}>
            <legend className={css.sectionTitle}>Card</legend>
            <p className={css.sectionNote}>Enter the account ID or the card number. With both, the card must belong to the account.</p>
            <div className={css.grid}>{KEY_INPUTS.map(input)}</div>
            {resolved && (
              <p className={css.resolved} data-testid="resolved">
                Account <span className="cd-mono">{resolved.acctId}</span>, card <span className="cd-mono">{resolved.cardNum}</span>
              </p>
            )}
            {cards && (
              <div className={css.cards} data-testid="card-choice">
                <Text size="sm" id="card-choice-title">
                  Cards on this account. Type the full number of the one to use.
                </Text>
                <ul aria-labelledby="card-choice-title">
                  {cards.map((card) => (
                    <li key={card} className="cd-mono">
                      {card}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </fieldset>
          <fieldset className={css.section}>
            <legend className={css.sectionTitle}>Transaction</legend>
            <p className={css.sectionNote} data-testid="proc-date-note">
              {serviceDate === null ? (
                <>
                  Processing date{' '}
                  <span className="cd-mono" data-testid="proc-date" data-source="device">
                    {today()}
                  </span>{' '}
                  on this device. The service sets the processing date when it checks the transaction.
                </>
              ) : (
                <>
                  Processing date{' '}
                  <span className="cd-mono" data-testid="proc-date" data-source="service">
                    {serviceDate}
                  </span>
                  , from the service.
                </>
              )}
            </p>
            <div className={css.grid}>{DETAIL_INPUTS.map(input)}</div>
          </fieldset>
          <fieldset className={css.section}>
            <legend className={css.sectionTitle}>Merchant</legend>
            <div className={css.grid}>{MERCHANT_INPUTS.map(input)}</div>
          </fieldset>
          <div className={classes.confirmBar}>
            <Radio.Group
              value={confirm}
              onChange={(value) => setConfirm(value === 'Y' || value === 'N' ? value : '')}
              label="Add this transaction now?"
              description={
                <span id="confirm-hint">
                  {review ? 'Yes adds the transaction shown. No clears the form without adding it.' : 'Leave both unselected to review it first.'}
                </span>
              }
              aria-describedby={describedBy('confirm', 'confirm-hint')}
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
            <MessageArea id={messageId} message={screenMessage}>
              {hint && (
                <p className={css.hint} data-testid="add-hint">
                  {hint}
                </p>
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
          </div>
          <div className={classes.formActions}>
            <Button variant="subtle" color="slate" onClick={clearAll} disabled={busy} data-testid="clear">
              Clear
              <KeyHint name="F4" />
            </Button>
            <Button type="submit" loading={busy} data-testid="submit">
              {submitLabel}
            </Button>
          </div>
        </form>
        <ReviewPanel review={review} values={values} />
      </div>
    </>
  );
}
