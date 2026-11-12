import { Button, Table, Text, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { transactionApi, type TransactionPage, type TransactionRow } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatCurrency } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { transactionMessages } from '@/messages/transaction';
import { openedFrom, readerHome, useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { useUser } from '@/session/SessionProvider';
import classes from './records.module.css';
import list from './CardListPage.module.css';
import tx from './TransactionPages.module.css';

const { message } = catalogue(transactionMessages);

export const TRANSACTIONS_PATH = '/transactions';

type Cursor = { start: string } | { after: string } | { before: string } | Record<string, never>;

interface Listing {
  rows: TransactionRow[];
  firstKey: string | null;
  lastKey: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  page: number;
}

const EMPTY: Listing = { rows: [], firstKey: null, lastKey: null, hasNext: false, hasPrevious: false, page: 1 };
const KEY = /^[0-9]{16}$/;

/** VAL-TRNL-001 as the service applies it: trailing spaces dropped, then 1 to 16 digits; blank means the first page. */
export function searchProblem(typed: string): boolean {
  const value = typed.replace(/ +$/, '');
  return value !== '' && !/^[0-9]{1,16}$/.test(value);
}

/**
 * BR-TRNL-003 and VAL-TRNL-002: the first non-blank selection decides, and only S
 * (either case) is valid. Returns that row, and whether its code is valid.
 */
export function firstSelection(codes: readonly string[]): { row: number; valid: boolean } | null {
  const row = codes.findIndex((code) => code.trim() !== '');
  if (row < 0) return null;
  return { row, valid: (codes[row] ?? '').trim().toUpperCase() === 'S' };
}

/** Where the page was, from the query (ARCH: only the first key and the page number). */
function restored(params: URLSearchParams): { first: string; page: number } | null {
  const first = params.get('first') ?? '';
  const page = Number(params.get('page') ?? '1');
  if (!KEY.test(first)) return null;
  return { first, page: Number.isInteger(page) && page >= 1 ? page : 1 };
}

/** COTRN00C as a web page: every transaction, ten at a time, from a Tran ID on. */
export function TransactionListPage() {
  const user = useUser();
  const navigate = useNavigate();
  const back = useBack(readerHome(user));
  const [params, setParams] = useSearchParams();
  const { enabled: codesOn } = useFunctionKeys();
  const [search, setSearch] = useState('');
  const [listing, setListing] = useState<Listing>(EMPTY);
  const [codes, setCodes] = useState<string[]>([]);
  const [badRow, setBadRow] = useState<number | null>(null);
  const [searchInvalid, setSearchInvalid] = useState(false);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  // The first page is on its way from the first render.
  const [busy, setBusy] = useState(true);
  const searchRef = useRef<HTMLInputElement>(null);
  const busyRef = useRef(true);
  const unexpected = useUnexpected();

  const remember = (first: string | null, page: number) => {
    setParams(first ? { first, page: String(page) } : {}, { replace: true, preventScrollReset: true });
  };

  const notice = (result: TransactionPage) => setScreenMessage(result.message ? { text: result.message, tone: result.severity ?? 'info' } : null);

  /** One listTransactions call, with busy already set; a failure shows its message and keeps the page. */
  const request = (cursor: Cursor): Promise<TransactionPage | null> =>
    unwrap(transactionApi.GET('/api/transactions', { params: { query: cursor } }))
      .catch(async (error: unknown) => {
        if (unexpected(error)) return null;
        if (error instanceof ApiError && error.code === 'INVALID_CURSOR' && !('start' in cursor)) {
          // The position is gone: page 1, with the service's message.
          const first = await fetchPage({});
          if (first) show(first, 1);
          setScreenMessage(problemMessage(error));
          return null;
        }
        setScreenMessage(problemMessage(error));
        return null;
      })
      .finally(() => {
        busyRef.current = false;
        setBusy(false);
      });

  const fetchPage = (cursor: Cursor) => {
    busyRef.current = true;
    setBusy(true);
    return request(cursor);
  };

  const show = (result: TransactionPage, page: number) => {
    const shownPage = result.hasPrevious ? page : 1;
    setListing({
      rows: result.items,
      firstKey: result.firstKey,
      lastKey: result.lastKey,
      hasNext: result.hasNext,
      hasPrevious: result.hasPrevious,
      page: shownPage,
    });
    setCodes([]);
    setBadRow(null);
    remember(result.firstKey, shownPage);
  };

  const load = async (cursor: Cursor, page: number) => {
    const result = await fetchPage(cursor);
    if (!result) return;
    show(result, page);
    notice(result);
  };

  useEffect(() => {
    const at = restored(params);
    void request(at ? { start: at.first } : {}).then((result) => {
      if (!result) return;
      show(result, at?.page ?? 1);
      notice(result);
    });
    searchRef.current?.focus();
    // First display only; later pages write the query themselves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const open = (row: TransactionRow) => {
    void navigate(`${TRANSACTIONS_PATH}/${row.tranId}`, { state: openedFrom(TRANSACTIONS_PATH) });
  };

  const enter = async (event?: FormEvent) => {
    event?.preventDefault();
    if (busyRef.current) return;
    // The selection comes first: S opens the view even when the search is not valid (ARCH, TRNL selection rules).
    const picked = codesOn ? firstSelection(listing.rows.map((_, i) => codes[i] ?? '')) : null;
    if (picked?.valid) {
      const row = listing.rows[picked.row];
      if (row) open(row);
      return;
    }
    if (searchProblem(search)) {
      setSearchInvalid(true);
      setBadRow(null);
      setScreenMessage(message('INVALID_SEARCH_TRAN_ID'));
      searchRef.current?.focus();
      return;
    }
    setSearchInvalid(false);
    if (picked) {
      setBadRow(picked.row);
      setScreenMessage(message('TRAN_INVALID_SELECTION'));
      document.getElementById(`tran-sel-${picked.row + 1}`)?.focus();
      return;
    }
    setBadRow(null);
    const typed = search.replace(/ +$/, '');
    if (typed !== '') await load({ start: typed }, 1);
    // A blank search shows the same page again, from its first key (DEF-TRNL-005).
    else await load(listing.firstKey ? { start: listing.firstKey } : {}, listing.page);
  };

  const next = async () => {
    if (busyRef.current) return;
    if (!listing.hasNext || !listing.lastKey) {
      setScreenMessage(message('TRAN_LIST_ALREADY_BOTTOM'));
      return;
    }
    const result = await fetchPage({ after: listing.lastKey });
    if (!result) return;
    if (result.items.length === 0) {
      // The rows after the page are gone: keep the page shown, with the bottom notice.
      setListing((current) => ({ ...current, hasNext: false }));
    } else {
      show(result, listing.page + 1);
    }
    notice(result);
  };

  const previous = async () => {
    if (busyRef.current) return;
    if (!listing.hasPrevious || !listing.firstKey) {
      setScreenMessage(message('TRAN_LIST_ALREADY_TOP'));
      return;
    }
    const result = await fetchPage({ before: listing.firstKey });
    if (!result) return;
    show(result, listing.page - 1);
    notice(result);
  };

  usePageKeys({
    back,
    escapeBack: true,
    previous: () => void previous(),
    next: () => void next(),
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'tran-list-message';
  return (
    <>
      <PageTitle
        title="Transactions"
        description="Every transaction in ID order, ten at a time. Enter a transaction ID to start the list there, then open a transaction to see its details."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={(event) => void enter(event)} noValidate aria-label="Transaction search">
        <div className={classes.searchRow}>
          <TextInput
            ref={searchRef}
            label="Start at transaction ID"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            description={<span id="tran-search-hint">Optional, up to 16 digits.</span>}
            error={searchInvalid ? true : undefined}
            aria-describedby={['tran-search-hint', searchInvalid ? messageId : null].filter(Boolean).join(' ')}
            className={tx.searchField}
            classNames={{ input: classes.keyInput }}
            data-testid="tran-search"
          />
          <div className={classes.searchActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              Search
            </Button>
          </div>
        </div>
        <div className={list.tableWrap}>
          <Table className={`${list.table} ${tx.table}`} verticalSpacing="sm" horizontalSpacing="md" highlightOnHover data-testid="tran-table">
            <caption className={list.caption}>
              Transactions, page <span data-testid="page-number">{listing.page}</span>
            </caption>
            <Table.Thead>
              <Table.Tr>
                {codesOn && <Table.Th className={list.selCol}>Sel</Table.Th>}
                <Table.Th>Transaction ID</Table.Th>
                <Table.Th>Date</Table.Th>
                <Table.Th>Description</Table.Th>
                <Table.Th className={tx.amountCol}>Amount</Table.Th>
                <Table.Th className={list.actionsCol}>
                  <span className={list.srOnly}>Actions</span>
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {listing.rows.map((row, i) => (
                <Table.Tr key={row.tranId} data-testid={`tran-row-${i + 1}`} data-invalid={badRow === i || undefined}>
                  {codesOn && (
                    <Table.Td>
                      <TextInput
                        id={`tran-sel-${i + 1}`}
                        value={codes[i] ?? ''}
                        onChange={(event) => {
                          const value = event.currentTarget.value;
                          setCodes((current) => {
                            const copy = [...current];
                            copy[i] = value;
                            return copy;
                          });
                        }}
                        maxLength={1}
                        size="xs"
                        autoComplete="off"
                        spellCheck={false}
                        aria-label={`Selection for transaction ${row.tranId}`}
                        aria-describedby={badRow === i ? messageId : undefined}
                        error={badRow === i ? true : undefined}
                        classNames={{ input: `${classes.keyInput} ${list.selInput}` }}
                        data-testid={`sel-${i + 1}`}
                      />
                    </Table.Td>
                  )}
                  <Table.Td className="cd-mono" data-testid={`row-id-${i + 1}`}>
                    {row.tranId}
                  </Table.Td>
                  <Table.Td className="cd-mono" data-testid={`row-date-${i + 1}`}>
                    {row.origDate || '\u2014'}
                  </Table.Td>
                  <Table.Td data-testid={`row-desc-${i + 1}`}>{row.description}</Table.Td>
                  <Table.Td className={tx.amountCol}>
                    <span className={classes.money}>{formatCurrency(row.amount)}</span>
                    <span className={classes.legacy} title="Mainframe format" data-testid={`row-amount-${i + 1}`}>
                      {row.amountDisplay}
                    </span>
                  </Table.Td>
                  <Table.Td className={list.actionsCol}>
                    <Button
                      size="compact-sm"
                      variant="light"
                      disabled={busy}
                      onClick={() => open(row)}
                      aria-label={`View transaction ${row.tranId}`}
                      data-testid={`view-${i + 1}`}
                    >
                      View
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {listing.rows.length === 0 && !busy && (
            <Text size="sm" c="dimmed" className={list.empty} data-testid="no-rows">
              No transactions to show.
            </Text>
          )}
        </div>
        <div className={list.footer}>
          <p className={classes.infoLine} data-testid="info-line">
            {codesOn ? 'Type S in Sel next to a transaction, or choose View, to see its details.' : 'Choose View to see a transaction\u2019s details.'}
          </p>
          <div className={list.pager}>
            <Button variant="default" onClick={() => void previous()} disabled={busy} data-testid="previous">
              Previous
              <KeyHint name="F7" />
            </Button>
            <Text size="sm" fw={600} className={list.pageLabel} aria-hidden="true">
              Page {listing.page}
            </Text>
            <Button variant="default" onClick={() => void next()} disabled={busy} data-testid="next">
              Next
              <KeyHint name="F8" />
            </Button>
          </div>
        </div>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage} />
      </div>
    </>
  );
}
