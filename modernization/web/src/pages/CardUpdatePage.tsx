import { Button, Text, TextInput } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode, type RefObject } from 'react';
import { useNavigate, useParams } from 'react-router';
import { accountApi, type CardUpdateForm } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { LeaveGuard } from '@/components/LeaveGuard';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { strip, stripTrailing } from '@/lib/javaStrip';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { accountMessages } from '@/messages/account';
import { cardMessages } from '@/messages/card';
import { useBack } from '@/lib/useBack';
import { useFocusAfterCommit } from '@/lib/useFocusAfterCommit';
import { problemMessage } from '@/lib/useReveal';
import { checkCardKeys, isEmptyInput, toProblem, type CardKeyField } from '@/validation/accountRules';
import { form as ruleForm, today } from '@/validation/forms';
import { CardKeyFields, keyFieldsOf, type CardKeyValues } from './CardKeyFields';
import { rememberCard } from './CardViewPage';
import classes from './records.module.css';

const { message } = catalogue({ ...accountMessages, ...cardMessages });
type MessageKey = Parameters<typeof message>[0];

export type CardField = 'embossedName' | 'activeStatus' | 'expiryMonth' | 'expiryYear';
export type CardValues = Record<CardField, string>;
const CARD_FIELDS: CardField[] = ['embossedName', 'activeStatus', 'expiryMonth', 'expiryYear'];

/** The spec §6.1 states; "missing" is a card from the list that no longer exists. */
type Stage = 'search' | 'loading' | 'details' | 'confirm' | 'saved' | 'missing';

const valuesOf = (form: CardUpdateForm): CardValues => ({
  embossedName: form.embossedName,
  activeStatus: form.activeStatus,
  expiryMonth: form.expiryMonth,
  expiryYear: form.expiryYear,
});

/** What the page sends: a one-digit month gets its leading zero (NUM-CRDU-003). */
export function cardChanges(values: CardValues): CardValues {
  return { ...values, expiryMonth: /^\d$/.test(values.expiryMonth) ? `0${values.expiryMonth}` : values.expiryMonth };
}

/**
 * VAL-CRDU-010 in the client: the values sent compared with those fetched.
 * Trailing spaces and the case of Y/N do not count (D-51); the case of the name does. The
 * service strips the status, month and year and the end of the name as Java does.
 */
export function isUnchanged(values: CardValues, form: CardUpdateForm): boolean {
  const sent = cardChanges(values);
  return (
    stripTrailing(sent.embossedName) === stripTrailing(form.embossedName) &&
    strip(sent.activeStatus).toUpperCase() === form.activeStatus &&
    strip(sent.expiryMonth) === form.expiryMonth &&
    strip(sent.expiryYear) === form.expiryYear
  );
}

const EMPTY_KEYS: CardKeyValues = { acctId: '', cardNum: '' };
const EMPTY_VALUES: CardValues = { embossedName: '', activeStatus: '', expiryMonth: '', expiryYear: '' };

function infoKey(stage: Stage, reviewing: boolean, failed: boolean): MessageKey | null {
  switch (stage) {
    case 'search':
      return 'CARD_KEYS_PROMPT';
    case 'details':
      return reviewing ? 'CARD_UPDATE_DETAILS' : 'CARD_UPDATE_SHOWN';
    case 'confirm':
      return failed ? 'CHANGES_UNSUCCESSFUL' : 'CHANGES_VALIDATED';
    case 'saved':
      return 'CHANGES_COMMITTED';
    default:
      return null;
  }
}

/**
 * COCRDUPC as a web page. /cards/edit asks for the keys (menu option 5);
 * /cards/:cardId/edit opens the card chosen on the list. Enter validates,
 * Save (F5) on the confirmation step writes with the version as If-Match.
 */
export function CardUpdatePage() {
  const { cardId } = useParams();
  const fromList = cardId !== undefined;
  const navigate = useNavigate();
  const back = useBack(fromList ? '/cards' : '/');
  const [stage, setStage] = useState<Stage>(fromList ? 'loading' : 'search');
  const [keys, setKeys] = useState<CardKeyValues>(EMPTY_KEYS);
  const [keyInvalid, setKeyInvalid] = useState<CardKeyField[]>([]);
  const [keyMarked, setKeyMarked] = useState<CardKeyField[]>([]);
  const [form, setForm] = useState<CardUpdateForm | null>(null);
  const [values, setValues] = useState<CardValues>(EMPTY_VALUES);
  const [fieldErrors, setFieldErrors] = useState<CardField[]>([]);
  /** Errors state, or a record changed by someone else under review. */
  const [reviewing, setReviewing] = useState(false);
  /** The values someone else saved, shown beside the user's edits after 409 RECORD_CHANGED (D-17, D-68). */
  const [current, setCurrent] = useState<CardUpdateForm | null>(null);
  /** The fields whose stored value moved between the form the user edited and that `current`. */
  const [moved, setMoved] = useState<CardField[]>([]);
  const [failed, setFailed] = useState(false);
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const acctRef = useRef<HTMLInputElement>(null);
  const cardRef = useRef<HTMLInputElement>(null);
  const fieldRefs: Record<CardField, RefObject<HTMLInputElement | null>> = {
    embossedName: useRef<HTMLInputElement>(null),
    activeStatus: useRef<HTMLInputElement>(null),
    expiryMonth: useRef<HTMLInputElement>(null),
    expiryYear: useRef<HTMLInputElement>(null),
  };
  const saveRef = useRef<HTMLButtonElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);

  const focusAfterCommit = useFocusAfterCommit(busy);
  const focusLater = (ref: RefObject<HTMLElement | null>) => focusAfterCommit(() => ref.current);

  const run = async (step: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await step();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const showDetails = useCallback((next: CardUpdateForm) => {
    setForm(next);
    setValues(valuesOf(next));
    setKeys({ acctId: next.acctId, cardNum: next.cardNum });
    setKeyInvalid([]);
    setKeyMarked([]);
    setFieldErrors([]);
    setReviewing(false);
    setCurrent(null);
    setMoved([]);
    setFailed(false);
    setRetryAt(null);
    setScreenMessage(null);
    setStage('details');
    rememberCard(next.acctId, next.cardId);
    focusAfterCommit(() => fieldRefs.embossedName.current);
    // fieldRefs holds stable refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusAfterCommit]);

  const toSearchNotFound = (error: unknown) => {
    setForm(null);
    setValues(EMPTY_VALUES);
    setStage('search');
    setKeyInvalid(error instanceof ApiError ? keyFieldsOf(error.code) : ['acctId']);
    setKeyMarked([]);
    setScreenMessage(problemMessage(error));
    focusLater(acctRef);
  };

  const toMissing = (error: unknown) => {
    setForm(null);
    setStage('missing');
    setScreenMessage(problemMessage(error));
    focusLater(backRef);
  };

  const fetchById = async (id: string) => unwrap(accountApi.GET('/api/cards/{cardId}/update-form', { params: { path: { cardId: id } } }));

  useEffect(() => {
    if (cardId === undefined) return;
    void run(async () => {
      try {
        showDetails(await fetchById(cardId));
      } catch (error) {
        toMissing(error);
      }
    });
    // The list path loads once per card.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cardId]);

  useEffect(() => {
    if (!fromList) acctRef.current?.focus();
  }, [fromList]);

  const lookup = async () => {
    const problem = checkCardKeys(keys.acctId, keys.cardNum);
    if (problem) {
      setKeyInvalid(problem.invalid);
      setKeyMarked(problem.invalid);
      setScreenMessage(problem.message);
      focusLater(problem.field === 'acctId' ? acctRef : cardRef);
      return;
    }
    await run(async () => {
      try {
        showDetails(await unwrap(accountApi.POST('/api/cards/update-lookup', { body: { acctId: keys.acctId, cardNum: keys.cardNum } })));
      } catch (error) {
        setKeyInvalid(error instanceof ApiError && !error.isNetwork ? keyFieldsOf(error.code) : []);
        setKeyMarked([]);
        setScreenMessage(problemMessage(error));
        focusLater(error instanceof ApiError && error.field === 'cardNum' ? cardRef : acctRef);
      }
    });
  };

  /** 409 RECORD_CHANGED: the current values become the base, the user's edits stay for review. */
  const review = (error: ApiError) => {
    const latest = error.body.current as CardUpdateForm | undefined;
    if (latest) {
      const before = form;
      setMoved(before ? CARD_FIELDS.filter((name) => latest[name] !== before[name]) : []);
      setForm(latest);
      setCurrent(latest);
    }
    setStage('details');
    setReviewing(true);
    setFailed(false);
    setFieldErrors([]);
    setScreenMessage(problemMessage(error));
    focusLater(fieldRefs.embossedName);
  };

  const fieldOf = (name: string | null): CardField => (CARD_FIELDS as string[]).includes(name ?? '') ? (name as CardField) : 'embossedName';

  const validate = async () => {
    if (!form) return;
    // Form cardUpdate as the service runs it; the stored expiry lets an expired card keep its date (D-51).
    const stored = { expiryMonth: form.expiryMonth, expiryYear: form.expiryYear };
    const broken = toProblem<CardField>(ruleForm('cardUpdate').validate(cardChanges(values), today(), null, stored));
    if (broken) {
      setFieldErrors(broken.invalid);
      setReviewing(true);
      setScreenMessage(broken.message);
      focusLater(fieldRefs[fieldOf(broken.field)]);
      return;
    }
    if (isUnchanged(values, form)) {
      setFieldErrors([]);
      setScreenMessage(message('NO_CHANGE'));
      focusLater(fieldRefs.embossedName);
      return;
    }
    const target = form;
    await run(async () => {
      try {
        await unwrap(
          accountApi.POST('/api/cards/{cardId}/validate', {
            params: { path: { cardId: target.cardId } },
            body: { version: target.version, changes: cardChanges(values) },
          }),
        );
        setFieldErrors([]);
        setScreenMessage(null);
        setFailed(false);
        setStage('confirm');
        focusLater(saveRef);
      } catch (error) {
        if (error instanceof ApiError && error.code === 'RECORD_CHANGED') return review(error);
        const invalid = error instanceof ApiError && Array.isArray(error.body.invalidFields) ? (error.body.invalidFields as string[]).filter((f): f is CardField => (CARD_FIELDS as string[]).includes(f)) : [];
        setFieldErrors(invalid);
        if (invalid.length > 0) setReviewing(true);
        setScreenMessage(problemMessage(error));
        focusLater(fieldRefs[fieldOf(error instanceof ApiError ? error.field : null)]);
      }
    });
  };

  const save = async () => {
    if (!form || stage !== 'confirm') return;
    const target = form;
    await run(async () => {
      try {
        const result = await unwrap(
          accountApi.PUT('/api/cards/{cardId}', {
            params: { path: { cardId: target.cardId }, header: { 'If-Match': `"${target.version}"` } },
            body: { changes: cardChanges(values) },
          }),
        );
        setForm(result.form);
        setValues(valuesOf(result.form));
        setCurrent(null);
        setMoved([]);
        setFailed(false);
        setRetryAt(null);
        setScreenMessage(null);
        setStage('saved');
        focusLater(saveRef);
      } catch (error) {
        if (error instanceof ApiError && error.code === 'RECORD_CHANGED') return review(error);
        // D-68: a lock or rewrite failure keeps the values, and Save may be tried again.
        setFailed(true);
        setRetryAt(error instanceof ApiError && error.code === 'BATCH_WINDOW' ? new Date(Date.now() + (error.retryAfterSeconds ?? 300) * 1000) : null);
        setScreenMessage(problemMessage(error));
        focusLater(saveRef);
      }
    });
  };

  const cancel = async () => {
    if (!form) return;
    const id = form.cardId;
    await run(async () => {
      try {
        showDetails(await fetchById(id));
      } catch (error) {
        if (error instanceof ApiError && error.code === 'CARD_NOT_FOUND' && !fromList) toSearchNotFound(error);
        else if (error instanceof ApiError && error.code === 'CARD_NOT_FOUND') toMissing(error);
        else setScreenMessage(problemMessage(error));
      }
    });
  };

  const afterSave = () => {
    if (fromList) {
      void navigate('/cards');
      return;
    }
    setForm(null);
    setValues(EMPTY_VALUES);
    setKeys(EMPTY_KEYS);
    setScreenMessage(null);
    setStage('search');
    focusLater(acctRef);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    if (stage === 'search') void lookup();
    else if (stage === 'details') void validate();
    else if (stage === 'confirm') focusLater(saveRef);
    else if (stage === 'saved') afterSave();
  };

  usePageKeys({
    back,
    escapeBack: true,
    save: stage === 'confirm' ? () => void save() : undefined,
    cancel: stage === 'confirm' ? () => void cancel() : undefined,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const editable = stage === 'details';
  const dirty = (stage === 'details' || stage === 'confirm') && form !== null && !isUnchanged(values, form);
  const info = infoKey(stage, reviewing || fieldErrors.length > 0, failed);
  const messageId = 'card-update-message';

  const field = (name: CardField, label: string, input: ReactNode) => (
    <div>
      <div className={classes.keyGroup}>
        {input}
        {fieldErrors.includes(name) && isEmptyInput(values[name]) && (
          <span className={classes.marker} aria-hidden="true" data-testid={`${name}-marker`}>
            *
          </span>
        )}
      </div>
      {current && moved.includes(name) && (
        <span className={classes.current} data-testid={`${name}-current`}>
          {label} now saved as <span className="cd-mono">{current[name] || '(blank)'}</span>
        </span>
      )}
    </div>
  );
  const inputProps = (name: CardField, hint?: string) => ({
    ref: fieldRefs[name],
    value: values[name],
    onChange: (event: React.ChangeEvent<HTMLInputElement>) => {
      const value = event.currentTarget.value;
      setValues((v) => ({ ...v, [name]: value }));
    },
    readOnly: !editable,
    autoComplete: 'off',
    spellCheck: false,
    error: fieldErrors.includes(name) ? true : undefined,
    'aria-describedby': [hint, fieldErrors.includes(name) ? messageId : null].filter(Boolean).join(' ') || undefined,
    'data-testid': name,
  });

  const showDetailsForm = form !== null && stage !== 'search' && stage !== 'missing';

  return (
    <>
      <LeaveGuard dirty={dirty} />
      <PageTitle
        title="Update credit card"
        description={fromList ? 'Change the name, status or expiry of the card you chose.' : 'Find a card by its account number and card number, then change its details.'}
        actions={
          <Button ref={backRef} variant="default" onClick={back}>
            {fromList ? 'Back to card list' : 'Back'}
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={submit} noValidate aria-describedby="card-update-info">
        <div className={classes.searchRow}>
          <CardKeyFields
            values={keys}
            onChange={setKeys}
            readOnly={stage !== 'search'}
            invalid={keyInvalid}
            marked={keyMarked}
            messageId={messageId}
            acctRef={acctRef}
            cardRef={cardRef}
          />
          {stage === 'search' && (
            <div className={classes.searchActions}>
              <Button type="submit" loading={busy} data-testid="submit">
                Find card
              </Button>
            </div>
          )}
        </div>

        {showDetailsForm && (
          <>
            <p className={classes.sectionLabel}>Card details</p>
            <div className={classes.formGrid}>
              {field(
                'embossedName',
                'Name',
                <TextInput {...inputProps('embossedName', 'name-hint')} label="Name on card" maxLength={50} description={<span id="name-hint">Letters and spaces, up to 50.</span>} style={{ flex: 1 }} />,
              )}
              {field(
                'activeStatus',
                'Status',
                <TextInput
                  {...inputProps('activeStatus', 'status-hint')}
                  label="Card active"
                  maxLength={1}
                  description={<span id="status-hint">Y for active, N for inactive.</span>}
                  classNames={{ input: classes.keyInput }}
                  w={160}
                />,
              )}
              <div>
                <div className={classes.expiry} role="group" aria-labelledby="expiry-label">
                  <span id="expiry-label" className={classes.srOnlyLabel}>
                    Expiry date
                  </span>
                  {field(
                    'expiryMonth',
                    'Month',
                    <TextInput
                      {...inputProps('expiryMonth', 'expiry-hint')}
                      label="Expiry month"
                      maxLength={2}
                      inputMode="numeric"
                      classNames={{ input: classes.keyInput }}
                      w={110}
                    />,
                  )}
                  <span className={classes.expirySep} aria-hidden="true">
                    /
                  </span>
                  {field(
                    'expiryYear',
                    'Year',
                    <TextInput {...inputProps('expiryYear', 'expiry-hint')} label="Expiry year" maxLength={4} inputMode="numeric" classNames={{ input: classes.keyInput }} w={120} />,
                  )}
                </div>
                <Text id="expiry-hint" size="xs" c="dimmed" mt={6}>
                  Month 01 to 12 and a four-digit year, not in the past.
                </Text>
              </div>
            </div>
          </>
        )}

        {info && (
          <p id="card-update-info" className={classes.infoLine} data-tone={stage === 'saved' ? 'done' : undefined} data-testid="info-line">
            {message(info).text}
          </p>
        )}

        {stage === 'details' && (
          <div className={classes.formActions}>
            <Button variant="default" onClick={() => void cancel()} disabled={busy} data-testid="cancel">
              Cancel
            </Button>
            <Button type="submit" loading={busy} data-testid="submit">
              Review changes
            </Button>
          </div>
        )}
        {stage === 'confirm' && (
          <div className={classes.confirmBar} data-testid="confirm-bar">
            <Text size="sm" fw={600}>
              {failed ? 'The changes were not saved. You can try again.' : 'Save these changes to the card?'}
            </Text>
            <div className={classes.searchActions} style={{ paddingTop: 0 }}>
              <Button variant="default" onClick={() => void cancel()} disabled={busy} data-testid="cancel">
                Cancel
                <KeyHint name="F12" />
              </Button>
              <Button ref={saveRef} onClick={() => void save()} loading={busy} data-testid="save">
                Save
                <KeyHint name="F5" />
              </Button>
            </div>
          </div>
        )}
        {stage === 'saved' && (
          <div className={classes.formActions}>
            <Button ref={saveRef} type="submit" data-testid="submit">
              {fromList ? 'Back to card list' : 'Update another card'}
            </Button>
          </div>
        )}
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage}>
          {retryAt && (
            <Text size="sm" mt={4} c="inherit">
              Try again after {formatClockTime(retryAt)}.
            </Text>
          )}
        </MessageArea>
      </div>
    </>
  );
}
