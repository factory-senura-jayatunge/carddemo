import { Badge, Button, Table, Text, TextInput } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { identityApi, type ListedUser, type UserPage } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { userMessages } from '@/messages/user';
import { openedFrom, useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { encodeUserKey } from '@/lib/userKey';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { useUser } from '@/session/SessionProvider';
import { fullName, PAGE_SIZE, printableId, TypeBadge, USER_ADD_PATH, userDeletePath, userEditPath, USERS_PATH, type UserListState } from './UserParts';
import list from './CardListPage.module.css';
import classes from './records.module.css';
import users from './UserPages.module.css';

const { message } = catalogue(userMessages);

type Position = { startKey: string } | { after: string } | { before: string } | Record<string, never>;

interface Listing {
  rows: ListedUser[];
  firstKey: string | null;
  lastKey: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  page: number;
  /** The search of the rows shown, as typed. */
  applied: string;
}

const EMPTY: Listing = {
  rows: [],
  firstKey: null,
  lastKey: null,
  hasNext: false,
  hasPrevious: false,
  page: 1,
  applied: '',
};
const searchOf = (typed: string): Position => (typed === '' ? {} : { startKey: encodeUserKey(typed) });

/**
 * BR-USRL-003 to BR-USRL-005 and VAL-USRL-001: the first non-blank selection decides; U or D
 * (either case) is valid. Returns that row and its action, or the row with no action when the
 * code is not valid.
 */
export function firstUserSelection(codes: readonly string[]): { row: number; action: 'update' | 'delete' | null } | null {
  const row = codes.findIndex((code) => code.trim() !== '');
  if (row < 0) return null;
  const code = (codes[row] ?? '').trim();
  return { row, action: code === 'U' || code === 'u' ? 'update' : code === 'D' || code === 'd' ? 'delete' : null };
}

const DESCRIPTION: Record<NonNullable<UserListState['intent']> | 'list', string> = {
  list: 'Everyone who can sign on, ten at a time. Search by user ID, then update or delete a user.',
  update: 'Find the user you want to update, then choose Update on their row.',
  delete: 'Find the user you want to delete, then choose Delete on their row.',
};

/** COUSR00C as a web page: ten users a page in user ID order, with a user ID search. */
export function UserListPage() {
  const me = useUser();
  const navigate = useNavigate();
  const location = useLocation();
  const routeState = (location.state ?? null) as UserListState | null;
  const back = useBack('/admin');
  const [search, setSearch] = useState('');
  const [listing, setListing] = useState<Listing>(EMPTY);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(routeState?.notice ?? null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const { enabled: codesOn } = useFunctionKeys();
  /** Selection codes by row; cleared whenever other rows are shown (D-55). */
  const [codes, setCodes] = useState<string[]>([]);
  const [badRow, setBadRow] = useState<number | null>(null);

  /** One listUsers call; the page number follows the response (a first page is always page 1). */
  const fetchPage = async (applied: string, position: Position, page: number): Promise<UserPage | null> => {
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await unwrap(
        identityApi.GET('/api/users', {
          params: { query: { size: PAGE_SIZE, ...position } },
        }),
      );
      setCodes([]);
      setBadRow(null);
      setListing({
        rows: result.items,
        firstKey: result.firstKey,
        lastKey: result.lastKey,
        hasNext: result.hasNext,
        hasPrevious: result.hasPrevious,
        page: result.hasPrevious ? page : 1,
        applied,
      });
      return result;
    } catch (error) {
      if (error instanceof ApiError && error.code === 'INVALID_CURSOR') {
        // The position is gone: page 1 of the same search, with the service's message.
        const first = await fetchPage(applied, searchOf(applied), 1);
        if (first) setScreenMessage(problemMessage(error));
        return null;
      }
      setCodes([]);
      setBadRow(null);
      setListing((current) => ({
        ...current,
        rows: [],
        firstKey: null,
        lastKey: null,
        hasNext: false,
        hasPrevious: false,
        applied,
      }));
      setScreenMessage(problemMessage(error));
      return null;
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const shownResponse = (result: UserPage | null) => {
    if (result) setScreenMessage(result.message ? { text: result.message, tone: result.severity ?? 'info' } : null);
  };

  useEffect(() => {
    void fetchPage('', {}, 1).then((result) => {
      // A result carried from the delete page stays until the next action.
      if (!routeState?.notice) shownResponse(result);
    });
    searchRef.current?.focus();
    // First display only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const open = (path: string) => void navigate(path, { state: openedFrom(USERS_PATH) });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    // The selection comes before the search, as on the legacy screen.
    const picked = codesOn ? firstUserSelection(listing.rows.map((_, i) => codes[i] ?? '')) : null;
    const row = picked ? listing.rows[picked.row] : undefined;
    if (picked && row) {
      if (picked.action) {
        open(picked.action === 'update' ? userEditPath(row.userId) : userDeletePath(row.userId));
        return;
      }
      // D-55: the message stays and nothing is fetched that could replace it (DEF-USRL-004).
      setBadRow(picked.row);
      setScreenMessage(message('USER_INVALID_SELECTION'));
      document.getElementById(`user-sel-${picked.row + 1}`)?.focus();
      return;
    }
    setBadRow(null);
    shownResponse(await fetchPage(search, searchOf(search), 1));
  };

  const next = async () => {
    if (busyRef.current) return;
    if (!listing.hasNext || !listing.lastKey) {
      // VAL-USRL-004: said by the page, no call.
      setScreenMessage(message('USER_LIST_ALREADY_BOTTOM'));
      return;
    }
    shownResponse(await fetchPage(listing.applied, { after: listing.lastKey }, listing.page + 1));
  };

  const previous = async () => {
    if (busyRef.current) return;
    if (!listing.hasPrevious || !listing.firstKey) {
      // VAL-USRL-003: said by the page, no call.
      setScreenMessage(message('USER_LIST_ALREADY_TOP'));
      return;
    }
    shownResponse(await fetchPage(listing.applied, { before: listing.firstKey }, listing.page - 1));
  };

  usePageKeys({
    back,
    escapeBack: true,
    previous: () => void previous(),
    next: () => void next(),
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'user-list-message';

  return (
    <>
      <PageTitle
        title="Users"
        description={DESCRIPTION[routeState?.intent ?? 'list']}
        actions={
          <>
            <Button variant="default" onClick={back}>
              Back
              <KeyHint name="F3" />
            </Button>
            <Button onClick={() => open(USER_ADD_PATH)} data-testid="add-user">
              Add user
            </Button>
          </>
        }
      />
      <form className={classes.searchCard} onSubmit={(event) => void submit(event)} noValidate aria-label="User search">
        <div className={classes.searchRow}>
          <TextInput
            ref={searchRef}
            label="User ID starts with"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            autoComplete="off"
            spellCheck={false}
            description={<span id="user-search-hint">Leave empty to list everyone. Letters match in either case.</span>}
            aria-describedby="user-search-hint"
            inputWrapperOrder={['label', 'input', 'description']}
            className={classes.acctField}
            classNames={{ input: classes.keyInput }}
            data-testid="user-search"
          />
          <div className={classes.searchActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              Search
            </Button>
          </div>
        </div>
        <div className={list.tableWrap}>
          <Table className={`${list.table} ${users.table}`} verticalSpacing="sm" horizontalSpacing="md" highlightOnHover data-testid="user-table">
            <caption className={list.caption}>
              Users, page <span data-testid="page-number">{listing.page}</span>
            </caption>
            <Table.Thead>
              <Table.Tr>
                {codesOn && <Table.Th className={list.selCol}>Sel</Table.Th>}
                <Table.Th>User ID</Table.Th>
                <Table.Th>Name</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th className={list.actionsCol}>
                  <span className={list.srOnly}>Actions</span>
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {listing.rows.map((row, i) => {
                const shown = printableId(row.userId);
                return (
                  <Table.Tr key={row.userId} data-testid={`user-row-${i + 1}`} data-invalid={badRow === i || undefined}>
                    {codesOn && (
                      <Table.Td data-cell="sel">
                        <TextInput
                          id={`user-sel-${i + 1}`}
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
                          aria-label={`Selection for user ${shown}`}
                          aria-describedby={badRow === i ? messageId : undefined}
                          error={badRow === i ? true : undefined}
                          classNames={{ input: `${classes.keyInput} ${list.selInput}` }}
                          data-testid={`sel-${i + 1}`}
                        />
                      </Table.Td>
                    )}
                    <Table.Td data-cell="id">
                      <span className={users.idCell} data-testid={`row-id-${i + 1}`}>
                        {shown}
                      </span>
                      {row.userId === me.userId && (
                        <Badge size="xs" radius="sm" variant="dot" color="brand" className={users.you}>
                          You
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td data-cell="name" className={users.nameCell} data-testid={`row-name-${i + 1}`}>
                      {fullName(row)}
                    </Table.Td>
                    <Table.Td data-cell="type">
                      <TypeBadge type={row.userType} testId={`row-type-${i + 1}`} />
                    </Table.Td>
                    <Table.Td data-cell="actions" className={list.actionsCol}>
                      <div className={list.rowActions}>
                        <Button
                          size="compact-sm"
                          variant="light"
                          disabled={busy}
                          onClick={() => open(userEditPath(row.userId))}
                          aria-label={`Update user ${shown}`}
                          data-testid={`update-${i + 1}`}
                        >
                          Update
                        </Button>
                        <Button
                          size="compact-sm"
                          variant="subtle"
                          color="red"
                          disabled={busy}
                          onClick={() => open(userDeletePath(row.userId))}
                          aria-label={`Delete user ${shown}`}
                          data-testid={`delete-${i + 1}`}
                        >
                          Delete
                        </Button>
                      </div>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
          {listing.rows.length === 0 && !busy && (
            <Text size="sm" c="dimmed" className={users.empty} data-testid="no-rows">
              No users to show.
            </Text>
          )}
        </div>
        <div className={list.footer}>
          <p className={classes.infoLine} data-testid="info-line">
            {codesOn ? 'Type U or D in Sel next to a user, or choose Update or Delete.' : 'Choose Update or Delete on a user\u2019s row.'}
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
