import { Button, Text, TextInput } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { accountApi, type AccountView } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MaskedValue } from '@/components/MaskedValue';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { accountMessages } from '@/messages/account';
import { problemMessage, useReveal } from '@/lib/useReveal';
import { readerHome, useBack } from '@/lib/useBack';
import { clearCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { useUser } from '@/session/SessionProvider';
import { checkAccountViewId } from '@/validation/accountRules';
import { Fact, Flag, Money } from './RecordParts';
import classes from './records.module.css';

const { message } = catalogue(accountMessages);

// FLOW-ACVW-010: the account field is red for these problems; a customer problem leaves it normal.
const ACCOUNT_FIELD_CODES = new Set(['ACCT_ID_REQUIRED', 'INVALID_ACCT_ID', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_LOOKUP_FAILED', 'XREF_LOOKUP_FAILED']);

type Reveal = ReturnType<typeof useReveal>;

function AccountPanel({ view }: { view: AccountView }) {
  return (
    <section className={classes.panel} aria-labelledby="account-panel-title" data-testid="account-panel">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>Account</p>
          <h2 id="account-panel-title" className={`${classes.panelTitle} cd-mono`}>
            {view.acctId}
          </h2>
        </div>
        <Flag value={view.activeStatus} yes="Active" no="Inactive" testId="acct-active" />
      </div>
      <div className={classes.balance}>
        <div>
          <Text size="sm" c="dimmed" fw={560}>
            Current balance
          </Text>
          <p className={classes.balanceAmount}>
            <Money value={view.currentBalance} display={view.currentBalanceDisplay} testId="acct-balance" />
          </p>
        </div>
      </div>
      <dl className={classes.facts}>
        <Fact label="Credit limit">
          <Money value={view.creditLimit} display={view.creditLimitDisplay} testId="acct-credit-limit" />
        </Fact>
        <Fact label="Cash credit limit">
          <Money value={view.cashCreditLimit} display={view.cashCreditLimitDisplay} testId="acct-cash-limit" />
        </Fact>
        <Fact label="Current cycle credit">
          <Money value={view.currCycCredit} display={view.currCycCreditDisplay} testId="acct-cycle-credit" />
        </Fact>
        <Fact label="Current cycle debit">
          <Money value={view.currCycDebit} display={view.currCycDebitDisplay} testId="acct-cycle-debit" />
        </Fact>
        <Fact label="Opened" testId="acct-open-date">
          {view.openDate}
        </Fact>
        <Fact label="Expires" testId="acct-expiry-date">
          {view.expirationDate}
        </Fact>
        <Fact label="Reissued" testId="acct-reissue-date">
          {view.reissueDate}
        </Fact>
        <Fact label="Account group" testId="acct-group">
          {view.groupId}
        </Fact>
      </dl>
    </section>
  );
}

function CustomerPanel({ view, reveal, onShow }: { view: AccountView; reveal: Reveal; onShow?: (field: string) => void }) {
  const customer = view.customer;
  const xref = view.crossReference;
  if (!customer || !xref) {
    return (
      <section className={classes.panel} data-empty aria-labelledby="customer-panel-title" data-testid="customer-panel-empty">
        <h2 id="customer-panel-title" className={classes.panelTitle}>
          Customer and card
        </h2>
        <Text size="sm" c="dimmed" mt={6}>
          This account has no card, so there is no customer to show.
        </Text>
      </section>
    );
  }
  const masked = (field: 'ssn' | 'dob' | 'govtIssuedId' | 'eftAccountId', label: string) => (
    <MaskedValue
      masked={customer[field]}
      clear={reveal.clear[field]}
      label={label}
      onShow={onShow && (() => onShow(field))}
      onHide={() => reveal.hide(field)}
      loading={reveal.loading === field}
      testId={`cust-${field}`}
    />
  );
  const name = [customer.firstName, customer.middleName, customer.lastName].filter(Boolean).join(' ');
  return (
    <section className={classes.panel} aria-labelledby="customer-panel-title" data-testid="customer-panel">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>Customer</p>
          <h2 id="customer-panel-title" className={classes.panelTitle} data-testid="cust-name">
            {name}
          </h2>
        </div>
        <span className="cd-mono" data-testid="cust-id">
          {customer.custId}
        </span>
      </div>
      <dl className={classes.facts}>
        <Fact label="Card number" wide>
          <MaskedValue
            masked={xref.cardNum}
            clear={reveal.clear.cardNum}
            label="card number"
            onShow={onShow && (() => onShow('cardNum'))}
            onHide={() => reveal.hide('cardNum')}
            loading={reveal.loading === 'cardNum'}
            testId="card-num"
          />
        </Fact>
        <Fact label="Address" wide>
          <address className={classes.address} data-testid="cust-address">
            {customer.addrLine1}
            {customer.addrLine2 && (
              <>
                <br />
                {customer.addrLine2}
              </>
            )}
            <br />
            <span data-testid="cust-city">{customer.city}</span>, <span data-testid="cust-state">{customer.stateCd}</span>{' '}
            <span data-testid="cust-zip">{customer.zip}</span>
            <br />
            <span data-testid="cust-country">{customer.countryCd}</span>
          </address>
        </Fact>
        <Fact label="Phone 1" testId="cust-phone1">
          {customer.phone1}
        </Fact>
        <Fact label="Phone 2" testId="cust-phone2">
          {customer.phone2}
        </Fact>
        <Fact label="Date of birth">{masked('dob', 'date of birth')}</Fact>
        <Fact label="SSN">{masked('ssn', 'SSN')}</Fact>
        <Fact label="Government ID">{masked('govtIssuedId', 'government ID')}</Fact>
        <Fact label="EFT account">{masked('eftAccountId', 'EFT account')}</Fact>
        <Fact label="FICO score" testId="cust-fico">
          {customer.ficoScore}
        </Fact>
        <Fact label="Primary card holder">
          <Flag value={customer.priCardHolderInd} testId="cust-primary" />
        </Fact>
      </dl>
    </section>
  );
}

/** COACTVWC as a web page: an account with its customer and card (read-only; ADMIN may Show masked values). */
export function AccountViewPage() {
  const user = useUser();
  const isAdmin = user.role === 'ADMIN';
  const back = useBack(readerHome(user));
  const [acctId, setAcctId] = useState('');
  const [view, setView] = useState<AccountView | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [acctInError, setAcctInError] = useState(false);
  const [busy, setBusy] = useState(false);
  const acctRef = useRef<HTMLInputElement>(null);
  useEffect(() => acctRef.current?.focus(), []);

  const show = useCallback((next: ScreenMessage | null, fieldRed: boolean) => {
    setScreenMessage(next);
    setAcctInError(fieldRed);
    window.requestAnimationFrame(() => acctRef.current?.focus());
  }, []);
  const unexpected = useUnexpected();
  const onRevealError = useCallback(
    (error: unknown) => {
      if (!unexpected(error)) show(problemMessage(error), false);
    },
    [show, unexpected],
  );
  const reveal = useReveal(onRevealError);

  const clearPanels = () => {
    setView(null);
    reveal.reset();
    clearCurrentAccount();
  };

  const lookup = async (event?: FormEvent) => {
    event?.preventDefault();
    if (busy) return;
    const problem = checkAccountViewId(acctId);
    if (problem) {
      // VAL-ACVW-001: a blank field gets the legacy "*".
      if (problem.code === 'ACCT_ID_REQUIRED') setAcctId('*');
      clearPanels();
      show(problem.message, true);
      return;
    }
    setBusy(true);
    try {
      const result = await unwrap(accountApi.GET('/api/accounts/{acctId}', { params: { path: { acctId } } }));
      reveal.reset();
      setView(result);
      setCurrentAccount({ acctId: result.acctId, custId: result.crossReference?.custId ?? null, cardId: result.crossReference?.cardId ?? null });
      show(result.message ? { text: result.message, tone: result.severity ?? 'info' } : null, false);
    } catch (error) {
      clearPanels();
      if (unexpected(error)) return;
      show(problemMessage(error), !(error instanceof ApiError) || ACCOUNT_FIELD_CODES.has(error.code));
    } finally {
      setBusy(false);
    }
  };

  const onShow = (field: string) => {
    if (!view) return;
    const shownAcct = view.acctId;
    void reveal.show(field, () =>
      unwrap(accountApi.POST('/api/accounts/{acctId}/unmask', { params: { path: { acctId: shownAcct } }, body: { fields: [field] } })),
    );
  };

  usePageKeys({ back, escapeBack: true, invalidKey: () => show(message('INVALID_KEY'), acctInError) });

  const messageId = 'account-view-message';
  return (
    <>
      <PageTitle
        title="Account view"
        description={
          isAdmin
            ? 'Look up an account, its customer and its card. Masked values can be shown one at a time; each reveal is recorded.'
            : 'Look up an account, its customer and its card.'
        }
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={(event) => void lookup(event)} noValidate aria-describedby="account-view-prompt">
        <div className={classes.searchRow}>
          <TextInput
            ref={acctRef}
            label="Account number"
            value={acctId}
            onChange={(event) => setAcctId(event.currentTarget.value)}
            maxLength={11}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            description="11 digits, for example 00000000001."
            error={acctInError ? true : undefined}
            aria-describedby={acctInError ? messageId : undefined}
            className={classes.acctField}
            classNames={{ input: classes.keyInput }}
            data-testid="acct-id"
          />
          <div className={classes.searchActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              View account
            </Button>
          </div>
        </div>
        <p id="account-view-prompt" className={classes.prompt} data-testid="info-line">
          {message('ACCOUNT_VIEW_PROMPT').text}
        </p>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage} />
      </div>
      {view && (
        <div className={classes.panels}>
          <AccountPanel view={view} />
          <CustomerPanel view={view} reveal={reveal} onShow={isAdmin ? onShow : undefined} />
        </div>
      )}
    </>
  );
}
