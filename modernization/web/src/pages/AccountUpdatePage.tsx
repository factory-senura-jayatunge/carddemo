import { Button, Text, TextInput } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { accountApi, type AccountUpdateForm } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { useFocusAfterCommit } from '@/lib/useFocusAfterCommit';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { accountMessages } from '@/messages/account';
import { problemMessage } from '@/lib/useReveal';
import { clearCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { checkAccountUpdateId, isEmptyInput, toProblem } from '@/validation/accountRules';
import {
  ACUP_FIELDS,
  detectChanges,
  hasCustomer,
  pageUnits,
  storedOf,
  UNITS,
  valuesOf,
  type AcupField,
  type AcupValues,
  type MaskedKey,
  type Unit,
} from '@/validation/accountUpdate';
import { form as ruleForm, today } from '@/validation/forms';
import css from './AccountUpdatePage.module.css';
import { Money } from './RecordParts';
import classes from './records.module.css';

const { message } = catalogue(accountMessages);

/** The page states of FLOW-ACUP-001; Errors is Details with red fields. */
type Stage = 'search' | 'details' | 'confirm' | 'saved' | 'failed';

const EMPTY = Object.fromEntries(ACUP_FIELDS.map((f) => [f, ''])) as AcupValues;
const MESSAGE_ID = 'account-update-message';
const isAcupField = (name: unknown): name is AcupField => (ACUP_FIELDS as unknown[]).includes(name);
const unit = (name: string) => UNITS.find((u) => u.name === name) as Unit;

/** Form keys whose stored value differs between two reads (a 409's `current` against the form the user edited). */
function movedKeys(before: AccountUpdateForm, after: AccountUpdateForm): Set<string> {
  const skip = new Set(['version', 'message', 'severity']);
  const keys = new Set<string>();
  for (const key of Object.keys(after) as (keyof AccountUpdateForm)[]) {
    if (skip.has(key) || after[key] === before[key]) continue;
    keys.add(key.replace(/Display$/, ''));
  }
  return keys;
}

interface PartSpec {
  field: AcupField;
  label: string;
  width: number;
  maxLength: number;
}

const DATE_PARTS = (prefix: string): PartSpec[] => [
  { field: `${prefix}Year` as AcupField, label: 'Year', width: 92, maxLength: 4 },
  { field: `${prefix}Month` as AcupField, label: 'Month', width: 76, maxLength: 2 },
  { field: `${prefix}Day` as AcupField, label: 'Day', width: 76, maxLength: 2 },
];
const PHONE_PARTS = (n: 1 | 2): PartSpec[] => [
  { field: `phone${n}Area` as AcupField, label: 'Area code', width: 96, maxLength: 3 },
  { field: `phone${n}Prefix` as AcupField, label: 'Prefix', width: 88, maxLength: 3 },
  { field: `phone${n}Line` as AcupField, label: 'Line', width: 96, maxLength: 4 },
];
const SSN_PARTS: PartSpec[] = [
  { field: 'ssn1', label: 'First 3', width: 84, maxLength: 3 },
  { field: 'ssn2', label: 'Middle 2', width: 84, maxLength: 2 },
  { field: 'ssn3', label: 'Last 4', width: 92, maxLength: 4 },
];
const ZIP_PARTS: PartSpec[] = [
  { field: 'zip5', label: 'ZIP', width: 100, maxLength: 5 },
  { field: 'zipPlus4', label: '+4', width: 80, maxLength: 4 },
];

const MASKED_LABEL: Record<MaskedKey, string> = { ssn: 'SSN', dob: 'date of birth', govtIssuedId: 'government ID', eftAccountId: 'EFT account' };

/**
 * COACTUPC as a web page (/accounts/edit, main menu option 2). Enter finds
 * the account, then checks the changed fields (D-49) in the browser and with
 * the service; Save (F5) on the confirmation step writes them with the
 * version as If-Match. Masked values are write-only: Change replaces one.
 */
export function AccountUpdatePage() {
  const navigate = useNavigate();
  // FLOW-ACUP-005: Back always goes to the main menu, and unsaved changes are dropped.
  const back = useCallback(() => void navigate('/'), [navigate]);
  const unexpected = useUnexpected();
  const [stage, setStage] = useState<Stage>('search');
  const [acctId, setAcctId] = useState('');
  const [acctInvalid, setAcctInvalid] = useState(false);
  const [acctMarked, setAcctMarked] = useState(false);
  const [form, setForm] = useState<AccountUpdateForm | null>(null);
  const [values, setValues] = useState<AcupValues>(EMPTY);
  const [replaced, setReplaced] = useState<ReadonlySet<MaskedKey>>(new Set());
  const [fieldErrors, setFieldErrors] = useState<AcupField[]>([]);
  /** After 409 RECORD_CHANGED: what someone else changed since the user's read (D-17). */
  const [moved, setMoved] = useState<Set<string>>(new Set());
  const [savedText, setSavedText] = useState<string | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const acctRef = useRef<HTMLInputElement>(null);
  const inputs = useRef<Partial<Record<AcupField, HTMLInputElement | null>>>({});
  const changeButtons = useRef<Partial<Record<MaskedKey, HTMLButtonElement | null>>>({});
  const saveRef = useRef<HTMLButtonElement>(null);
  const nextRef = useRef<HTMLButtonElement>(null);

  const later = useFocusAfterCommit(busy);
  const focusField = (field: AcupField) =>
    later(() => {
      const masked = UNITS.find((u) => u.masked && u.fields.includes(field))?.masked;
      return inputs.current[field] ?? (masked ? changeButtons.current[masked] : null);
    });

  useEffect(() => acctRef.current?.focus(), []);

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

  const showDetails = (next: AccountUpdateForm) => {
    setForm(next);
    setValues(valuesOf(next));
    setReplaced(new Set());
    setFieldErrors([]);
    setMoved(new Set());
    setAcctId(next.acctId);
    setAcctInvalid(false);
    setAcctMarked(false);
    setScreenMessage(null);
    setSavedText(null);
    setRetryAt(null);
    setStage('details');
    // BR-ACUP-010: a successful fetch names the current account, customer and card.
    setCurrentAccount({ acctId: next.acctId, custId: next.custId ?? null, cardId: next.cardId ?? null });
    focusField('activeStatus');
  };

  /** Search with the account number red: a refused or failed fetch shows nothing of the record (DEF-ACUP-019). */
  const toSearchProblem = (error: unknown) => {
    if (unexpected(error)) {
      clearCurrentAccount();
      return;
    }
    setForm(null);
    setValues(EMPTY);
    setReplaced(new Set());
    setFieldErrors([]);
    setMoved(new Set());
    setStage('search');
    setAcctInvalid(!(error instanceof ApiError && error.isNetwork));
    setAcctMarked(false);
    setScreenMessage(problemMessage(error));
    clearCurrentAccount();
    later(() => acctRef.current);
  };

  const fetchForm = (id: string) => unwrap(accountApi.GET('/api/accounts/{acctId}/update-form', { params: { path: { acctId: id } } }));

  const lookup = async () => {
    const problem = checkAccountUpdateId(acctId);
    if (problem) {
      setAcctInvalid(true);
      setAcctMarked(problem.blank.length > 0);
      setScreenMessage(problem.message);
      later(() => acctRef.current);
      return;
    }
    await run(async () => {
      try {
        showDetails(await fetchForm(acctId));
      } catch (error) {
        toSearchProblem(error);
      }
    });
  };

  /** The changed units of the inputs, compared with the stored values the page knows. */
  const pending = (base: AccountUpdateForm) => detectChanges(values, storedOf(base), pageUnits(base, replaced));

  /** 409 RECORD_CHANGED: `current` becomes the stored values and version; the user's inputs stay (D-17). */
  const review = (error: ApiError) => {
    const latest = error.body.current as AccountUpdateForm | undefined;
    if (latest && form) {
      setMoved(movedKeys(form, latest));
      setForm(latest);
    }
    setStage('details');
    setFieldErrors([]);
    setScreenMessage(problemMessage(error));
    focusField('activeStatus');
  };

  const refused = (error: unknown) => {
    const invalid =
      error instanceof ApiError && Array.isArray(error.body.invalidFields) ? (error.body.invalidFields as unknown[]).filter(isAcupField) : [];
    setFieldErrors(invalid);
    setScreenMessage(problemMessage(error));
    const field = error instanceof ApiError && isAcupField(error.field) ? error.field : 'activeStatus';
    focusField(invalid[0] ?? field);
  };

  const validate = async () => {
    if (!form) return;
    const found = pending(form);
    if (found.units.length === 0) {
      setFieldErrors([]);
      setScreenMessage(message('NO_CHANGE'));
      focusField('activeStatus');
      return;
    }
    // Form accountUpdate on the changed entries, as the service runs it (D-49); unchanged fields count as valid.
    const broken = toProblem<AcupField>(ruleForm('accountUpdate').validate(found.changes, today(), found.changedFields, storedOf(form)));
    if (broken) {
      setFieldErrors(broken.invalid);
      setScreenMessage(broken.message);
      focusField(broken.invalid[0] ?? broken.field);
      return;
    }
    const target = form;
    await run(async () => {
      try {
        await unwrap(
          accountApi.POST('/api/accounts/{acctId}/validate', {
            params: { path: { acctId: target.acctId } },
            body: { version: target.version, changes: found.changes },
          }),
        );
        setFieldErrors([]);
        setScreenMessage(null);
        setStage('confirm');
        later(() => saveRef.current);
      } catch (error) {
        if (unexpected(error)) return;
        if (error instanceof ApiError && error.code === 'RECORD_CHANGED') review(error);
        else refused(error);
      }
    });
  };

  const save = async () => {
    if (!form || stage !== 'confirm') return;
    const target = form;
    const { changes } = pending(target);
    await run(async () => {
      try {
        const result = await unwrap(
          accountApi.PUT('/api/accounts/{acctId}', {
            params: { path: { acctId: target.acctId }, header: { 'If-Match': `"${target.version}"` } },
            body: { changes },
          }),
        );
        setForm(result.form);
        setValues(valuesOf(result.form));
        setReplaced(new Set());
        setMoved(new Set());
        setSavedText(result.message);
        setScreenMessage(null);
        setStage('saved');
        later(() => nextRef.current);
      } catch (error) {
        if (unexpected(error)) return;
        if (error instanceof ApiError && error.code === 'RECORD_CHANGED') {
          review(error);
          return;
        }
        // ERR-ACUP-004: nothing was saved; the next Enter starts a new search.
        setStage('failed');
        setRetryAt(error instanceof ApiError && error.code === 'BATCH_WINDOW' ? new Date(Date.now() + (error.retryAfterSeconds ?? 300) * 1000) : null);
        setScreenMessage(problemMessage(error));
        later(() => nextRef.current);
      }
    });
  };

  /** Cancel (F12): read the account again and drop the edits (BR-ACUP-004). */
  const cancel = async () => {
    if (!form) return;
    const id = form.acctId;
    await run(async () => {
      try {
        showDetails(await fetchForm(id));
      } catch (error) {
        setAcctId(id);
        toSearchProblem(error);
      }
    });
  };

  /** BR-ACUP-009: after a save attempt, an empty search. */
  const restart = () => {
    setForm(null);
    setValues(EMPTY);
    setReplaced(new Set());
    setFieldErrors([]);
    setMoved(new Set());
    setAcctId('');
    setAcctInvalid(false);
    setAcctMarked(false);
    setScreenMessage(null);
    setSavedText(null);
    setRetryAt(null);
    setStage('search');
    later(() => acctRef.current);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    if (stage === 'search') void lookup();
    else if (stage === 'details') void validate();
    else if (stage === 'confirm') later(() => saveRef.current);
    else restart();
  };

  const shown = stage === 'details' || stage === 'confirm';
  usePageKeys({
    back,
    escapeBack: true,
    save: stage === 'confirm' ? () => void save() : undefined,
    cancel: shown ? () => void cancel() : stage === 'saved' || stage === 'failed' ? restart : undefined,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const editable = stage === 'details';
  const red = (field: AcupField) => fieldErrors.includes(field);
  const set = (field: AcupField, value: string) => setValues((v) => ({ ...v, [field]: value }));
  const stored = form ? storedOf(form) : {};

  const marker = (field: AcupField) =>
    red(field) && isEmptyInput(values[field]) ? (
      <span className={classes.marker} aria-hidden="true" data-testid={`${field}-marker`}>
        *
      </span>
    ) : null;

  const nowSaved = (key: string, shownValue: ReactNode) =>
    moved.has(key) ? (
      <span className={css.moved} data-testid={`${key}-moved`}>
        Changed by someone else; now saved as <span className="cd-mono">{shownValue || '(blank)'}</span>
      </span>
    ) : null;

  const describedBy = (field: AcupField, hint?: string) => [hint, red(field) ? MESSAGE_ID : null].filter(Boolean).join(' ') || undefined;

  const textInput = (field: AcupField, label: string, opts: { width?: number; maxLength: number; numeric?: boolean; hint?: string; ariaLabel?: string; hideLabel?: boolean }) => {
    const hintId = opts.hint ? `${field}-hint` : undefined;
    return (
      <div className={classes.keyGroup}>
        <TextInput
          ref={(el) => {
            inputs.current[field] = el;
          }}
          label={label}
          aria-label={opts.ariaLabel}
          value={values[field]}
          onChange={(event) => set(field, event.currentTarget.value)}
          readOnly={!editable}
          maxLength={opts.maxLength}
          inputMode={opts.numeric ? 'numeric' : undefined}
          autoComplete="off"
          spellCheck={false}
          description={opts.hint ? <span id={hintId}>{opts.hint}</span> : undefined}
          error={red(field) ? true : undefined}
          aria-describedby={describedBy(field, hintId)}
          w={opts.width}
          className={opts.width ? undefined : css.grow}
          classNames={{ input: opts.numeric ? classes.keyInput : undefined, label: opts.hideLabel ? classes.srOnlyLabel : undefined }}
          data-testid={field}
        />
        {marker(field)}
      </div>
    );
  };

  const single = (field: AcupField, label: string, opts: Parameters<typeof textInput>[2]) => (
    <div className={css.field} data-moved={moved.has(field) || undefined}>
      {textInput(field, label, opts)}
      {nowSaved(field, stored[field])}
    </div>
  );

  const parts = (key: string, legend: string, specs: PartSpec[], sep: string, storedText: string, hint?: string) => (
    <fieldset className={css.group} data-moved={specs.some((p) => moved.has(p.field)) || undefined} data-testid={`${key}-group`}>
      <legend className={css.legend}>{legend}</legend>
      <div className={css.parts}>
        {specs.map((p, i) => (
          <div key={p.field} className={css.part}>
            {i > 0 && sep && (
              <span className={css.sep} aria-hidden="true">
                {sep}
              </span>
            )}
            {textInput(p.field, p.label, { width: p.width, maxLength: p.maxLength, numeric: true, ariaLabel: `${legend} ${p.label.toLowerCase()}` })}
          </div>
        ))}
      </div>
      {hint && <Text className={css.hint}>{hint}</Text>}
      {specs.some((p) => moved.has(p.field)) && nowSaved(specs[0]?.field ?? key, storedText)}
    </fieldset>
  );

  const dateText = (prefix: string) => [stored[`${prefix}Year` as AcupField], stored[`${prefix}Month` as AcupField], stored[`${prefix}Day` as AcupField]].filter(Boolean).join('-');

  /** A write-only masked value (D-09): the mask and Change, or the inputs for a new value and Keep current. */
  const masked = (key: MaskedKey, legend: string, maskedValue: string | null | undefined, editor: ReactNode) => {
    const open = replaced.has(key);
    const toggle = () => {
      const fields = unit(key === 'ssn' ? 'ssn' : key).fields;
      setValues((v) => ({ ...v, ...Object.fromEntries(fields.map((f) => [f, ''])) }));
      setFieldErrors((errors) => errors.filter((f) => !fields.includes(f)));
      setReplaced((current) => {
        const next = new Set(current);
        if (open) next.delete(key);
        else next.add(key);
        return next;
      });
      if (open) later(() => changeButtons.current[key]);
      else focusField(fields[0] as AcupField);
    };
    return (
      <div className={css.masked} data-testid={`${key}-masked-field`}>
        {open ? (
          editor
        ) : (
          <div>
            <span className={css.legend} id={`${key}-label`}>
              {legend}
            </span>
            <span className={`cd-mono ${css.maskedValue}`} aria-labelledby={`${key}-label`} data-testid={`${key}-masked`}>
              {maskedValue || '\u2014'}
            </span>
          </div>
        )}
        {editable && (
          <Button
            ref={(el) => {
              changeButtons.current[key] = el;
            }}
            size="compact-sm"
            variant="subtle"
            onClick={toggle}
            aria-label={open ? `Keep the current ${MASKED_LABEL[key]}` : `Change ${MASKED_LABEL[key]}`}
            data-testid={`${key}-${open ? 'keep' : 'change'}`}
            className={css.maskedAction}
          >
            {open ? 'Keep current' : 'Change'}
          </Button>
        )}
      </div>
    );
  };

  const readOnlyMoney = (key: 'currentBalance' | 'currCycCredit' | 'currCycDebit', label: string) =>
    form && (
      <div data-moved={moved.has(key) || undefined} className={css.fact}>
        <dt>{label}</dt>
        <dd>
          <Money value={form[key]} display={form[`${key}Display`]} testId={key} />
          {moved.has(key) && (
            <span className={css.moved} data-testid={`${key}-moved`}>
              Changed by someone else
            </span>
          )}
        </dd>
      </div>
    );

  const info = (() => {
    switch (stage) {
      case 'search':
        return message('ACCOUNT_UPDATE_PROMPT').text;
      case 'details':
        return message('ACCOUNT_UPDATE_DETAILS').text;
      case 'confirm':
        return message('CHANGES_VALIDATED').text;
      case 'saved':
        return savedText ?? message('CHANGES_COMMITTED').text;
      case 'failed':
        return message('CHANGES_UNSUCCESSFUL').text;
    }
  })();

  const customer = form ? hasCustomer(form) : false;

  return (
    <>
      <PageTitle
        title="Update account"
        description="Find an account by its number, change its details, check them, then save."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={submit} noValidate aria-describedby="account-update-info">
        <div className={classes.searchRow}>
          <div className={classes.keyGroup}>
            <TextInput
              ref={acctRef}
              label="Account number"
              value={acctId}
              onChange={(event) => setAcctId(event.currentTarget.value)}
              readOnly={stage !== 'search'}
              maxLength={11}
              inputMode="numeric"
              autoComplete="off"
              spellCheck={false}
              description={<span id="acct-id-hint">11 digits.</span>}
              error={acctInvalid ? true : undefined}
              aria-describedby={['acct-id-hint', acctInvalid ? MESSAGE_ID : null].filter(Boolean).join(' ')}
              className={classes.acctField}
              classNames={{ input: classes.keyInput }}
              data-testid="acct-id"
            />
            {acctMarked && (
              <span className={classes.marker} aria-hidden="true" data-testid="acct-id-marker">
                *
              </span>
            )}
          </div>
          {stage === 'search' && (
            <div className={classes.searchActions}>
              <Button type="submit" loading={busy} data-testid="submit">
                Find account
              </Button>
            </div>
          )}
        </div>

        <div className={classes.messageSlot}>
          <MessageArea id={MESSAGE_ID} message={screenMessage}>
            {retryAt && (
              <Text size="sm" mt={4} c="inherit">
                Try again after {formatClockTime(retryAt)}.
              </Text>
            )}
          </MessageArea>
        </div>

        {form && stage !== 'search' && (
          <div className={css.sections}>
            <section className={css.section} aria-labelledby="acup-account">
              <h2 id="acup-account" className={css.sectionTitle}>
                Account
              </h2>
              <div className={css.row}>
                {single('activeStatus', 'Account status', { width: 150, maxLength: 1, hint: 'Y active, N inactive.' })}
                {single('groupId', 'Account group', { width: 200, maxLength: 10, hint: 'Up to 10 characters.' })}
              </div>
              <div className={css.row}>
                {single('creditLimit', 'Credit limit', { width: 200, maxLength: 15, numeric: true, hint: `Stored ${form.creditLimitDisplay.trim()}` })}
                {single('cashCreditLimit', 'Cash credit limit', {
                  width: 200,
                  maxLength: 15,
                  numeric: true,
                  hint: `Stored ${form.cashCreditLimitDisplay.trim()}`,
                })}
              </div>
              <div className={css.row}>
                {parts('openYear', 'Opened', DATE_PARTS('open'), '', dateText('open'))}
                {parts('expiryYear', 'Expires', DATE_PARTS('expiry'), '', dateText('expiry'))}
                {parts('reissueYear', 'Reissued', DATE_PARTS('reissue'), '', dateText('reissue'))}
              </div>
              <dl className={`${classes.facts} ${css.balances}`}>
                {readOnlyMoney('currentBalance', 'Current balance')}
                {readOnlyMoney('currCycCredit', 'Cycle credits')}
                {readOnlyMoney('currCycDebit', 'Cycle debits')}
              </dl>
            </section>

            <section className={css.section} aria-labelledby="acup-customer">
              <h2 id="acup-customer" className={css.sectionTitle}>
                Customer
                {customer && (
                  <span className={css.titleNote}>
                    ID <span className="cd-mono" data-testid="custId">{form.custId}</span>
                  </span>
                )}
              </h2>
              {customer ? (
                <>
                  <div className={css.row}>
                    {single('firstName', 'First name', { maxLength: 25 })}
                    {single('middleName', 'Middle name', { maxLength: 25 })}
                    {single('lastName', 'Last name', { maxLength: 25 })}
                  </div>
                  <div className={css.row}>
                    {masked('ssn', 'SSN', form.ssn, parts('ssn1', 'SSN', SSN_PARTS, '-', ''))}
                    {masked('dob', 'Date of birth', form.dob, parts('dobYear', 'Date of birth', DATE_PARTS('dob'), '', ''))}
                    {single('ficoScore', 'FICO score', { width: 120, maxLength: 3, numeric: true, hint: '300 to 850.' })}
                  </div>
                  <div className={css.row}>
                    {masked('govtIssuedId', 'Government ID', form.govtIssuedId, single('govtIssuedId', 'Government ID', { width: 260, maxLength: 20 }))}
                    {masked('eftAccountId', 'EFT account', form.eftAccountId, single('eftAccountId', 'EFT account', { width: 200, maxLength: 10, numeric: true }))}
                    {single('priCardHolderInd', 'Primary card holder', { width: 170, maxLength: 1, hint: 'Y or N.' })}
                  </div>
                </>
              ) : (
                <p className={css.empty} data-testid="no-customer">
                  This account has no card, so there are no customer details to update.
                </p>
              )}
            </section>

            {customer && (
              <section className={css.section} aria-labelledby="acup-contact">
                <h2 id="acup-contact" className={css.sectionTitle}>
                  Address and phone
                </h2>
                <div className={css.row}>{single('addrLine1', 'Address line 1', { maxLength: 50 })}</div>
                <div className={css.row}>{single('addrLine2', 'Address line 2', { maxLength: 50 })}</div>
                <div className={css.row}>
                  {single('city', 'City', { maxLength: 50 })}
                  {single('stateCd', 'State', { width: 100, maxLength: 2 })}
                  {parts('zip5', 'ZIP code', ZIP_PARTS, '-', stored.zip ?? '')}
                  <dl className={`${css.fact} ${css.inlineFact}`}>
                    <dt className={css.legend}>Country</dt>
                    <dd className="cd-mono" data-testid="countryCd">
                      {form.countryCd || '\u2014'}
                    </dd>
                  </dl>
                </div>
                <div className={css.row}>
                  {parts('phone1Area', 'Phone 1', PHONE_PARTS(1), '', stored.phone1 ?? '')}
                  {parts('phone2Area', 'Phone 2', PHONE_PARTS(2), '', stored.phone2 ?? '')}
                </div>
              </section>
            )}
          </div>
        )}

        <div className={css.actionBar} data-stage={stage}>
          <p id="account-update-info" className={classes.infoLine} data-tone={stage === 'saved' ? 'done' : undefined} data-testid="info-line">
            {info}
          </p>
          <div className={css.actions}>
            {shown && (
              <Button variant="default" onClick={() => void cancel()} disabled={busy} data-testid="cancel">
                Cancel
                <KeyHint name="F12" />
              </Button>
            )}
            {stage === 'details' && (
              <Button type="submit" loading={busy} data-testid="submit">
                Check changes
              </Button>
            )}
            {stage === 'confirm' && (
              <Button ref={saveRef} onClick={() => void save()} loading={busy} data-testid="save">
                Save
                <KeyHint name="F5" />
              </Button>
            )}
            {(stage === 'saved' || stage === 'failed') && (
              <Button ref={nextRef} type="submit" data-testid="submit">
                Update another account
              </Button>
            )}
          </div>
        </div>
      </form>
    </>
  );
}
