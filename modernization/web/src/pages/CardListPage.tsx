import { Button, Table, Text, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { accountApi, type CardPage, type CardRow } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MaskedValue } from '@/components/MaskedValue';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { cardMessages } from '@/messages/card';
import { openedFrom, readerHome, useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { setCurrentAccount } from '@/session/currentAccount';
import { useUser } from '@/session/SessionProvider';
import { checkCardFilters, isBlankFilter, type CardKeyField } from '@/validation/accountRules';
import { Flag } from './RecordParts';
import classes from './records.module.css';
import list from './CardListPage.module.css';

const { message } = catalogue(cardMessages);
type MessageKey = Parameters<typeof message>[0];

interface Filters {
  acctId: string;
  cardNum: string;
}
type Cursor = { start: string } | { after: string } | { before: string } | Record<string, never>;

interface Listing {
  rows: CardRow[];
  firstKey: string | null;
  lastKey: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  page: number;
  /** The filters of the rows shown (BR-CRDL-004). */
  applied: Filters;
}

const NO_FILTER: Filters = { acctId: '', cardNum: '' };
const EMPTY_LISTING: Listing = { rows: [], firstKey: null, lastKey: null, hasNext: false, hasPrevious: false, page: 1, applied: NO_FILTER };
const PAGE_SIZE = 7;

// BR-CRDL-008: the information line is blank with these notices.
const HIDES_INFO: MessageKey[] = ['NO_RECORDS_FOUND', 'NO_PREVIOUS_PAGES', 'NO_MORE_PAGES'];
const hidesInfo = (text: string | null) => text !== null && HIDES_INFO.some((key) => message(key).text === text);

const sameFilter = (a: string, b: string) => (isBlankFilter(a) ? '' : a) === (isBlankFilter(b) ? '' : b);
const filtersChanged = (typed: Filters, applied: Filters) => !sameFilter(typed.acctId, applied.acctId) || !sameFilter(typed.cardNum, applied.cardNum);

type Action = 'S' | 'U';
interface CodeProblem {
  key: MessageKey;
  rows: number[];
}

/**
 * The selection codes with the 3270 keys on (VAL-CRDL-004, VAL-CRDL-005),
 * upper-cased first (Q-CRDL-007). An ADMIN reads only, so U is not a valid code for one.
 */
export function checkCodes(codes: readonly string[], allowed: readonly Action[]): CodeProblem | { row: number; action: Action } | null {
  const normal = codes.map((c) => c.trim().toUpperCase());
  const picked = normal.flatMap((c, i) => (c === 'S' || c === 'U' ? [i] : []));
  const wrong = normal.flatMap((c, i) => (c !== '' && !(allowed as readonly string[]).includes(c) ? [i] : []));
  if (picked.length > 1) return { key: 'SELECT_ONE_RECORD', rows: [...new Set([...picked, ...wrong])].sort((a, b) => a - b) };
  if (wrong.length > 0) return { key: 'INVALID_ACTION_CODE', rows: wrong };
  const [row] = picked;
  return row === undefined ? null : { row, action: normal[row] as Action };
}

/** COCRDLIC as a web page: seven cards a page, filtered by account or card number. */
export function CardListPage() {
  const user = useUser();
  const isAdmin = user.role === 'ADMIN';
  const navigate = useNavigate();
  const back = useBack(readerHome(user));
  const { enabled: codesOn } = useFunctionKeys();
  const [filters, setFilters] = useState<Filters>(NO_FILTER);
  const [listing, setListing] = useState<Listing>(EMPTY_LISTING);
  const [codes, setCodes] = useState<string[]>([]);
  const [badRows, setBadRows] = useState<number[]>([]);
  const [filterError, setFilterError] = useState<CardKeyField[]>([]);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [infoShown, setInfoShown] = useState(true);
  const [endReported, setEndReported] = useState(false);
  const [busy, setBusy] = useState(false);
  const [clearCards, setClearCards] = useState<Record<string, string>>({});
  const [revealing, setRevealing] = useState<string | null>(null);
  const acctRef = useRef<HTMLInputElement>(null);
  const cardRef = useRef<HTMLInputElement>(null);
  const codeRefs = useRef<(HTMLInputElement | null)[]>([]);
  const busyRef = useRef(false);

  const notify = (next: ScreenMessage | null, info: boolean) => {
    setScreenMessage(next);
    setInfoShown(info);
  };

  /** One listCards call; the page number follows the response (a first page is always page 1). */
  const fetchPage = async (applied: Filters, cursor: Cursor, page: number): Promise<CardPage | null> => {
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await unwrap(
        accountApi.POST('/api/cards/search', { body: { acctId: applied.acctId, cardNum: applied.cardNum, size: PAGE_SIZE, ...cursor } }),
      );
      setListing({
        rows: result.items,
        firstKey: result.firstKey,
        lastKey: result.lastKey,
        hasNext: result.hasNext,
        hasPrevious: result.hasPrevious,
        page: result.hasPrevious ? page : 1,
        applied,
      });
      setCodes([]);
      setBadRows([]);
      setFilterError([]);
      setClearCards({});
      return result;
    } catch (error) {
      if (error instanceof ApiError && error.code === 'INVALID_CURSOR') {
        // The position is gone: page 1 with the same filters, with the service's message.
        const first = await fetchPage(applied, {}, 1);
        if (first) notify(problemMessage(error), true);
        return null;
      }
      // ERR-CRDL-001: no rows, page number kept; Previous and Next act as at both ends.
      setListing((current) => ({ ...current, rows: [], hasNext: false, hasPrevious: false, applied }));
      setCodes([]);
      setBadRows([]);
      setClearCards({});
      notify(problemMessage(error), true);
      return null;
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const shownResponse = (result: CardPage | null) => {
    if (!result) return;
    const text = result.message;
    notify(text ? { text, tone: result.severity ?? 'info' } : null, !hidesInfo(text));
  };

  useEffect(() => {
    void fetchPage(NO_FILTER, {}, 1).then(shownResponse);
    acctRef.current?.focus();
    // First display only (FLOW-CRDL-003).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Filter rules, then the code rules; true when the step may go on. */
  const checksPass = (withCodes: boolean): { ok: false } | { ok: true; pick: { row: number; action: Action } | null } => {
    const bad = checkCardFilters(filters.acctId, filters.cardNum);
    if (bad) {
      setFilterError(bad.invalid);
      setBadRows([]);
      notify(bad.message, false);
      (bad.field === 'acctId' ? acctRef : cardRef).current?.focus();
      return { ok: false };
    }
    setFilterError([]);
    if (!withCodes || !codesOn) return { ok: true, pick: null };
    const result = checkCodes(
      listing.rows.map((_, i) => codes[i] ?? ''),
      isAdmin ? ['S'] : ['S', 'U'],
    );
    if (result && 'key' in result) {
      setBadRows(result.rows);
      notify(message(result.key), true);
      const [first] = result.rows;
      if (first !== undefined) codeRefs.current[first]?.focus();
      return { ok: false };
    }
    setBadRows([]);
    return { ok: true, pick: result };
  };

  const open = (row: CardRow, action: Action) => {
    setCurrentAccount({ acctId: row.acctId, custId: null, cardId: row.cardId });
    void navigate(action === 'S' ? `/cards/${row.cardId}` : `/cards/${row.cardId}/edit`, { state: openedFrom('/cards') });
  };

  const enter = async (event?: FormEvent) => {
    event?.preventDefault();
    if (busyRef.current) return;
    setEndReported(false);
    const checked = checksPass(true);
    if (!checked.ok) return;
    if (checked.pick) {
      const row = listing.rows[checked.pick.row];
      if (row) open(row, checked.pick.action);
      return;
    }
    if (filtersChanged(filters, listing.applied) || !listing.firstKey) {
      shownResponse(await fetchPage({ ...filters }, {}, 1));
    } else {
      shownResponse(await fetchPage(listing.applied, { start: listing.firstKey }, listing.page));
    }
  };

  const next = async () => {
    if (busyRef.current) return;
    if (!checksPass(true).ok) return;
    setCodes([]);
    if (filtersChanged(filters, listing.applied)) {
      setEndReported(false);
      shownResponse(await fetchPage({ ...filters }, {}, 1));
      return;
    }
    if (listing.hasNext && listing.lastKey) {
      const result = await fetchPage(listing.applied, { after: listing.lastKey }, listing.page + 1);
      if (result && !result.hasNext) setEndReported(true);
      shownResponse(result);
      return;
    }
    const result = await fetchPage(listing.applied, listing.firstKey ? { start: listing.firstKey } : {}, listing.page);
    if (!result) return;
    if (endReported) notify(message('NO_MORE_PAGES'), false);
    else notify(message('NO_MORE_RECORDS'), true);
    setEndReported(true);
  };

  const previous = async () => {
    if (busyRef.current) return;
    setEndReported(false);
    if (!checksPass(true).ok) return;
    setCodes([]);
    if (filtersChanged(filters, listing.applied)) {
      shownResponse(await fetchPage({ ...filters }, {}, 1));
      return;
    }
    if (listing.hasPrevious && listing.firstKey) {
      shownResponse(await fetchPage(listing.applied, { before: listing.firstKey }, listing.page - 1));
      return;
    }
    const result = await fetchPage(listing.applied, listing.firstKey ? { start: listing.firstKey } : {}, 1);
    if (result) notify(message('NO_PREVIOUS_PAGES'), false);
  };

  const reveal = async (row: CardRow) => {
    setRevealing(row.cardId);
    try {
      const result = await unwrap(
        accountApi.POST('/api/cards/{cardId}/unmask', { params: { path: { cardId: row.cardId } }, body: { fields: ['cardNum'] } }),
      );
      const value = result.fields.cardNum;
      if (value) setClearCards((current) => ({ ...current, [row.cardId]: value }));
    } catch (error) {
      notify(problemMessage(error), infoShown);
    } finally {
      setRevealing(null);
    }
  };
  const hide = (cardId: string) => setClearCards(({ [cardId]: _gone, ...rest }) => rest);

  usePageKeys({
    back,
    escapeBack: true,
    previous: () => void previous(),
    next: () => void next(),
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const rowActionsOff = filterError.length > 0 || busy;
  const messageId = 'card-list-message';
  const red = (field: CardKeyField) => filterError.includes(field);
  const describedBy = (field: CardKeyField, hint: string) => [hint, red(field) ? messageId : null].filter(Boolean).join(' ');

  return (
    <>
      <PageTitle
        title="Credit cards"
        description={isAdmin ? 'Every card, seven at a time. Filter by account or card number; open a card to view it.' : 'Every card, seven at a time. Filter by account or card number, then view or update a card.'}
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={(event) => void enter(event)} noValidate aria-label="Card filters">
        <div className={classes.searchRow}>
          <TextInput
            ref={acctRef}
            label="Account number"
            value={filters.acctId}
            onChange={(event) => setFilters({ ...filters, acctId: event.currentTarget.value })}
            maxLength={11}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            description={<span id="acct-filter-hint">Optional, 11 digits.</span>}
            error={red('acctId') ? true : undefined}
            aria-describedby={describedBy('acctId', 'acct-filter-hint')}
            className={classes.acctField}
            classNames={{ input: classes.keyInput }}
            data-testid="acct-filter"
          />
          <TextInput
            ref={cardRef}
            label="Card number"
            value={filters.cardNum}
            onChange={(event) => setFilters({ ...filters, cardNum: event.currentTarget.value })}
            maxLength={16}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            description={<span id="card-filter-hint">Optional, 16 digits.</span>}
            error={red('cardNum') ? true : undefined}
            aria-describedby={describedBy('cardNum', 'card-filter-hint')}
            className={classes.cardField}
            classNames={{ input: classes.keyInput }}
            data-testid="card-filter"
          />
          <div className={classes.searchActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              Search
            </Button>
          </div>
        </div>
        <div className={list.tableWrap}>
          <Table className={list.table} verticalSpacing="sm" horizontalSpacing="md" highlightOnHover aria-describedby="card-list-info" data-testid="card-table">
            <caption className={list.caption}>
              Cards, page <span data-testid="page-number">{listing.page}</span>
            </caption>
            <Table.Thead>
              <Table.Tr>
                {codesOn && <Table.Th className={list.selCol}>Sel</Table.Th>}
                <Table.Th>Account</Table.Th>
                <Table.Th>Card number</Table.Th>
                <Table.Th>Active</Table.Th>
                <Table.Th className={list.actionsCol}>
                  <span className={list.srOnly}>Actions</span>
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {listing.rows.map((row, i) => {
                const last4 = row.cardNum.slice(-4);
                return (
                  <Table.Tr key={row.cardId} data-testid={`card-row-${i + 1}`} data-invalid={badRows.includes(i) || undefined}>
                    {codesOn && (
                      <Table.Td>
                        <TextInput
                          ref={(element) => {
                            codeRefs.current[i] = element;
                          }}
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
                          aria-label={`Action code for card ending ${last4}`}
                          aria-describedby={badRows.includes(i) ? messageId : undefined}
                          error={badRows.includes(i) ? true : undefined}
                          classNames={{ input: `${classes.keyInput} ${list.selInput}` }}
                          data-testid={`sel-${i + 1}`}
                        />
                      </Table.Td>
                    )}
                    <Table.Td className="cd-mono" data-testid={`row-acct-${i + 1}`}>
                      {row.acctId}
                    </Table.Td>
                    <Table.Td>
                      <MaskedValue
                        masked={row.cardNum}
                        clear={clearCards[row.cardId]}
                        label={`card number ending ${last4}`}
                        onShow={isAdmin ? () => void reveal(row) : undefined}
                        onHide={() => hide(row.cardId)}
                        loading={revealing === row.cardId}
                        testId={`row-card-${i + 1}`}
                      />
                    </Table.Td>
                    <Table.Td>
                      <Flag value={row.activeStatus} testId={`row-active-${i + 1}`} />
                    </Table.Td>
                    <Table.Td className={list.actionsCol}>
                      <div className={list.rowActions}>
                        <Button size="compact-sm" variant="light" disabled={rowActionsOff} onClick={() => open(row, 'S')} aria-label={`View card ending ${last4}`} data-testid={`view-${i + 1}`}>
                          View
                        </Button>
                        {!isAdmin && (
                          <Button size="compact-sm" variant="subtle" disabled={rowActionsOff} onClick={() => open(row, 'U')} aria-label={`Update card ending ${last4}`} data-testid={`update-${i + 1}`}>
                            Update
                          </Button>
                        )}
                      </div>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
          {listing.rows.length === 0 && !busy && (
            <Text size="sm" c="dimmed" className={list.empty} data-testid="no-rows">
              No cards to show.
            </Text>
          )}
        </div>
        <div className={list.footer}>
          <p id="card-list-info" className={classes.infoLine} data-testid="info-line">
            {infoShown ? message('CARD_LIST_PROMPT').text : ''}
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
