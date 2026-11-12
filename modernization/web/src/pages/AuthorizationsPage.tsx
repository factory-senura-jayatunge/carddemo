import { Button, Table, Text, TextInput } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { authorizationApi, type AuthorizationPage, type AuthorizationRow, type AuthorizationSummary } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { approvalLabel, formatCount, matchLabel, summaryAddress, summaryName } from '@/lib/authorizationDisplay';
import { formatCurrency } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { authorizationMessages } from '@/messages/authorization';
import { openedFrom } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { Coded, Fact } from './RecordParts';
import classes from './records.module.css';
import auth from './AuthorizationsPage.module.css';

const { message } = catalogue(authorizationMessages);

// The account field is marked for the problems that are about the typed ID.
const ACCOUNT_FIELD_CODES = new Set(['ACCOUNT_ID_REQUIRED', 'ACCOUNT_ID_NOT_NUMERIC', 'ACCOUNT_NOT_FOUND']);

interface Listing {
  rows: AuthorizationRow[];
  firstKey: string | null;
  lastKey: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  page: number;
}

const EMPTY: Listing = { rows: [], firstKey: null, lastKey: null, hasNext: false, hasPrevious: false, page: 1 };
const listingOf = (page: AuthorizationPage, number: number): Listing => ({
  rows: page.items,
  firstKey: page.firstKey,
  lastKey: page.lastKey,
  hasNext: page.hasNext,
  hasPrevious: page.hasPrevious,
  page: page.hasPrevious ? number : 1,
});

type Cursor = { after: string } | { before: string } | Record<string, never>;

const fetchPage = (acctId: string, cursor: Cursor) =>
  unwrap(authorizationApi.GET('/api/accounts/{acctId}/authorizations', { params: { path: { acctId }, query: { size: '5', ...cursor } } }));

function CustomerPanel({ summary }: { summary: AuthorizationSummary }) {
  const customer = summary.customer;
  if (!customer) {
    return (
      <section className={classes.panel} data-empty aria-labelledby="auth-customer-title" data-testid="auth-customer-empty">
        <h2 id="auth-customer-title" className={classes.panelTitle}>
          Customer
        </h2>
        <Text size="sm" c="dimmed" mt={6}>
          No customer to show for this account.
        </Text>
      </section>
    );
  }
  return (
    <section className={classes.panel} aria-labelledby="auth-customer-title" data-testid="auth-customer">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>Customer</p>
          <h2 id="auth-customer-title" className={`${classes.panelTitle} ${auth.keepSpaces}`} data-testid="auth-name">
            {summaryName(customer.name)}
          </h2>
        </div>
        <span className="cd-mono" data-testid="auth-cust-id">
          {customer.custId}
        </span>
      </div>
      <dl className={classes.facts}>
        <Fact label="Address" wide>
          <address className={`${classes.address} ${auth.keepSpaces}`}>
            <span data-testid="auth-address1">{summaryAddress(customer.addressLine1)}</span>
            <br />
            <span data-testid="auth-address2">{summaryAddress(customer.addressLine2)}</span>
          </address>
        </Fact>
        <Fact label="Phone" testId="auth-phone">
          {customer.phone.replace(/ +$/, '')}
        </Fact>
      </dl>
    </section>
  );
}

function TotalsPanel({ summary }: { summary: AuthorizationSummary }) {
  return (
    <section className={classes.panel} aria-labelledby="auth-totals-title" data-testid="auth-totals">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>Account</p>
          <h2 id="auth-totals-title" className={`${classes.panelTitle} cd-mono`} data-testid="auth-acct-id">
            {summary.acctId}
          </h2>
        </div>
      </div>
      <div className={auth.stats}>
        <div className={auth.stat} data-tone="approved">
          <p className={auth.statLabel}>Approved</p>
          <p className={auth.statAmount} data-testid="auth-approved-amount">
            {formatCurrency(summary.approvedAmount)}
          </p>
          <p className={auth.statCount}>
            <span data-testid="auth-approved-count">{formatCount(summary.approvedCount)}</span> {summary.approvedCount === 1 ? 'request' : 'requests'}
          </p>
        </div>
        <div className={auth.stat} data-tone="declined">
          <p className={auth.statLabel}>Declined</p>
          <p className={auth.statAmount} data-testid="auth-declined-amount">
            {formatCurrency(summary.declinedAmount)}
          </p>
          <p className={auth.statCount}>
            <span data-testid="auth-declined-count">{formatCount(summary.declinedCount)}</span> {summary.declinedCount === 1 ? 'request' : 'requests'}
          </p>
        </div>
      </div>
      <dl className={classes.facts}>
        <Fact label="Credit limit" testId="auth-credit-limit">
          {formatCurrency(summary.creditLimit)}
        </Fact>
        <Fact label="Cash limit" testId="auth-cash-limit">
          {formatCurrency(summary.cashLimit)}
        </Fact>
        <Fact label="Pending credit balance" testId="auth-credit-balance">
          {formatCurrency(summary.creditBalance)}
        </Fact>
        <Fact label="Cash balance" testId="auth-cash-balance">
          {formatCurrency(summary.cashBalance)}
        </Fact>
      </dl>
      {!summary.summaryFound && (
        <Text size="sm" c="dimmed" mt="md" data-testid="auth-no-summary">
          No authorizations are recorded for this account.
        </Text>
      )}
    </section>
  );
}

/** The list as cards on a narrow screen, where eight columns cannot fit; each card opens its authorization. */
function AuthorizationCards({ rows }: { rows: AuthorizationRow[] }) {
  return (
    <ol className={auth.cards} aria-labelledby="auth-list-title" data-testid="auth-cards">
      {rows.map((row, i) => (
        <li key={row.authId} className={auth.card} data-testid={`auth-row-${i + 1}`}>
          <div className={auth.cardTop}>
            <Link
              to={`/authorizations/${row.authId}`}
              state={openedFrom('/authorizations')}
              className={`${auth.cardLink} cd-mono`}
              aria-label={`View authorization ${row.transactionId}`}
              data-testid={`view-${i + 1}`}
            >
              <span data-testid={`row-tran-${i + 1}`}>{row.transactionId}</span>
            </Link>
            <span className={auth.cardAmount} data-testid={`row-amount-${i + 1}`}>
              {formatCurrency(row.approvedAmount)}
            </span>
          </div>
          <p className={auth.cardMeta}>
            <span className="cd-mono" data-testid={`row-date-${i + 1}`}>
              {row.requestDate}
            </span>{' '}
            <span className="cd-mono" data-testid={`row-time-${i + 1}`}>
              {row.requestTime}
            </span>
            <span aria-hidden="true"> · </span>
            <span className={auth.srOnly}>, </span>
            <span data-testid={`row-type-${i + 1}`}>{row.authType}</span>
          </p>
          <p className={auth.cardCodes}>
            <span className={auth.cardCodeLabel}>Result</span>
            <Coded value={row.approval} note={approvalLabel(row.approval)} tone={row.approval === 'A' ? 'good' : 'bad'} testId={`row-approval-${i + 1}`} />
            <span className={auth.cardCodeLabel}>Match</span>
            <Coded value={row.matchStatus} note={matchLabel(row.matchStatus)} testId={`row-match-${i + 1}`} />
          </p>
        </li>
      ))}
    </ol>
  );
}

/** COPAUS0C as a web page: an account's pending authorization totals and its authorizations, five at a time. */
export function AuthorizationsPage() {
  const navigate = useNavigate();
  const unexpected = useUnexpected();
  // FLOW-AUSM-002: the current account is shown at once.
  const [acctId, setAcctId] = useState(() => readCurrentAccount()?.acctId ?? '');
  const [summary, setSummary] = useState<AuthorizationSummary | null>(null);
  const [listing, setListing] = useState<Listing>(EMPTY);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [acctInError, setAcctInError] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const acctRef = useRef<HTMLInputElement>(null);
  const compact = useMediaQuery('(max-width: 40em)', false, { getInitialValueInEffect: false });

  const run = async (work: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await work();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const fail = useCallback(
    (error: unknown) => {
      if (unexpected(error)) return;
      setScreenMessage(problemMessage(error));
    },
    [unexpected],
  );

  /** The header, totals and first page of one account (BR-AUSM-001 to BR-AUSM-004). */
  const lookup = (typed: string) =>
    run(async () => {
      if (typed === '') {
        // An empty path segment cannot reach the service; the field is required (VAL-AUSM-001).
        setSummary(null);
        setListing(EMPTY);
        setScreenMessage(message('ACCOUNT_ID_REQUIRED'));
        setAcctInError(true);
        acctRef.current?.focus();
        return;
      }
      const [head, page] = await Promise.allSettled([
        unwrap(authorizationApi.GET('/api/accounts/{acctId}/authorizations/summary', { params: { path: { acctId: typed } } })),
        fetchPage(typed, {}),
      ]);
      if (head.status === 'rejected') {
        setSummary(null);
        setListing(EMPTY);
        const error: unknown = head.reason;
        setAcctInError(error instanceof ApiError && ACCOUNT_FIELD_CODES.has(error.code));
        fail(error);
        acctRef.current?.focus();
        return;
      }
      const shown = head.value;
      setSummary(shown);
      setAcctInError(false);
      const current = readCurrentAccount();
      setCurrentAccount({
        acctId: shown.acctId,
        custId: shown.customer?.custId ?? null,
        cardId: current?.acctId === shown.acctId ? current.cardId : null,
      });
      if (page.status === 'rejected') {
        setListing(EMPTY);
        fail(page.reason);
        return;
      }
      setListing(listingOf(page.value, 1));
      setScreenMessage(shown.message ? { text: shown.message, tone: shown.severity ?? 'error' } : null);
    });

  useEffect(() => {
    if (acctId !== '') void lookup(acctId);
    acctRef.current?.focus();
    // First display only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void lookup(acctId);
  };

  const turn = (direction: 'next' | 'previous') =>
    run(async () => {
      if (!summary) return;
      // VAL-AUSM-005: the ends of the list are the web's own messages; no call is made.
      if (direction === 'next' && !listing.hasNext) {
        setScreenMessage(message('AUTH_LIST_BOTTOM'));
        return;
      }
      if (direction === 'previous' && !listing.hasPrevious) {
        setScreenMessage(message('AUTH_LIST_TOP'));
        return;
      }
      const cursor: Cursor = direction === 'next' ? { after: listing.lastKey ?? '' } : { before: listing.firstKey ?? '' };
      try {
        const page = await fetchPage(summary.acctId, cursor);
        setListing(listingOf(page, listing.page + (direction === 'next' ? 1 : -1)));
        setScreenMessage(null);
      } catch (error) {
        setListing(EMPTY);
        fail(error);
      }
    });

  const back = () => void navigate('/');
  usePageKeys({
    back,
    escapeBack: true,
    previous: () => void turn('previous'),
    next: () => void turn('next'),
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'authorizations-message';
  return (
    <>
      <PageTitle
        title="Pending authorizations"
        description="Look up an account to see its authorization totals and its latest requests, five at a time."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={submit} noValidate aria-label="Account search">
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
              Show authorizations
            </Button>
          </div>
        </div>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage} />
      </div>
      {summary && (
        <>
          <div className={classes.panels}>
            <CustomerPanel summary={summary} />
            <TotalsPanel summary={summary} />
          </div>
          <section className={auth.listSection} aria-labelledby="auth-list-title">
            <h2 id="auth-list-title" className={auth.listTitle}>
              Authorizations
            </h2>
            <div className={compact ? undefined : auth.tableWrap}>
              {compact ? (
                <AuthorizationCards rows={listing.rows} />
              ) : (
                <Table className={auth.table} verticalSpacing="sm" horizontalSpacing="md" highlightOnHover data-testid="auth-table">
                  <caption className={auth.srOnly}>
                    Authorizations of account {summary.acctId}, newest first, page <span data-testid="page-number">{listing.page}</span>
                  </caption>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Date</Table.Th>
                      <Table.Th>Time</Table.Th>
                      <Table.Th>Transaction</Table.Th>
                      <Table.Th>Type</Table.Th>
                      <Table.Th>Result</Table.Th>
                      <Table.Th>Match</Table.Th>
                      <Table.Th className={auth.amountCol}>Approved amount</Table.Th>
                      <Table.Th className={auth.actionsCol}>
                        <span className={auth.srOnly}>Actions</span>
                      </Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {listing.rows.map((row, i) => (
                      <Table.Tr key={row.authId} data-testid={`auth-row-${i + 1}`}>
                        <Table.Td className="cd-mono" data-testid={`row-date-${i + 1}`}>
                          {row.requestDate}
                        </Table.Td>
                        <Table.Td className="cd-mono" data-testid={`row-time-${i + 1}`}>
                          {row.requestTime}
                        </Table.Td>
                        <Table.Td className="cd-mono" data-testid={`row-tran-${i + 1}`}>
                          {row.transactionId}
                        </Table.Td>
                        <Table.Td data-testid={`row-type-${i + 1}`}>{row.authType}</Table.Td>
                        <Table.Td>
                          <Coded value={row.approval} note={approvalLabel(row.approval)} tone={row.approval === 'A' ? 'good' : 'bad'} testId={`row-approval-${i + 1}`} />
                        </Table.Td>
                        <Table.Td>
                          <Coded value={row.matchStatus} note={matchLabel(row.matchStatus)} testId={`row-match-${i + 1}`} />
                        </Table.Td>
                        <Table.Td className={auth.amountCol} data-testid={`row-amount-${i + 1}`}>
                          {formatCurrency(row.approvedAmount)}
                        </Table.Td>
                        <Table.Td className={auth.actionsCol}>
                          <Button
                            component={Link}
                            to={`/authorizations/${row.authId}`}
                            state={openedFrom('/authorizations')}
                            size="compact-sm"
                            variant="light"
                            aria-label={`View authorization ${row.transactionId}`}
                            data-testid={`view-${i + 1}`}
                          >
                            View
                          </Button>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
              {listing.rows.length === 0 && !busy && (
                <Text size="sm" c="dimmed" className={auth.empty} data-testid="no-rows">
                  No authorizations to show.
                </Text>
              )}
            </div>
            <div className={auth.pager}>
              <Button variant="default" onClick={() => void turn('previous')} disabled={busy} data-testid="previous">
                Previous
                <KeyHint name="F7" />
              </Button>
              <Text size="sm" fw={600} className={auth.pageLabel} aria-hidden="true">
                Page {listing.page}
              </Text>
              <Button variant="default" onClick={() => void turn('next')} disabled={busy} data-testid="next">
                Next
                <KeyHint name="F8" />
              </Button>
            </div>
          </section>
        </>
      )}
    </>
  );
}
