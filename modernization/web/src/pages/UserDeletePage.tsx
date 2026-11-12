import { Button, Loader, Text } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { identityApi, type UserView } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatClockTime } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { userMessages } from '@/messages/user';
import { useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { decodeUserKey } from '@/lib/userKey';
import { useFunctionKeys } from '@/prefs/FunctionKeys';
import { fullName, printableId, retryAtOf, TypeBadge, USERS_PATH, type UserListState } from './UserParts';
import { MissingUser, useUserRecord } from './useUserRecord';
import classes from './records.module.css';
import users from './UserPages.module.css';

const { message } = catalogue(userMessages);

/** COUSR03C as a web page: the user a route names, read-only, deleted after a confirmation that names them. */
export function UserDeletePage() {
  const { userKey = '' } = useParams();
  return <UserDelete key={userKey} userKey={userKey} />;
}

function UserDelete({ userKey }: { userKey: string }) {
  const navigate = useNavigate();
  const back = useBack(USERS_PATH);
  const [record, setRecord] = useState<UserView | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(() =>
    decodeUserKey(userKey) === null ? message('USER_ID_NOT_FOUND') : null,
  );
  const [retryAt, setRetryAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const { enabled: keysOn } = useFunctionKeys();
  /** Bumped to move focus to the user's name: on display and after an answer that changed what is shown. */
  const [focusTick, setFocusTick] = useState(0);

  const { stage, markMissing, retryable, reload } = useUserRecord(userKey, {
    onLoaded: (user) => {
      setRecord(user);
      setScreenMessage(null);
    },
    onFailed: setScreenMessage,
  });

  useEffect(() => {
    // The name, not the Delete button: a stray Enter must not delete.
    if (stage === 'ready') titleRef.current?.focus();
  }, [stage, focusTick]);

  const remove = async () => {
    if (!record || busyRef.current) return;
    const target = record;
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await unwrap(
        identityApi.DELETE('/api/users/key/{userKey}', {
          params: {
            path: { userKey },
            header: { 'If-Match': `"${target.version}"` },
          },
        }),
      );
      const state: UserListState = {
        notice: { text: result.message, tone: result.severity },
      };
      void navigate(USERS_PATH, { replace: true, state });
    } catch (error) {
      if (error instanceof ApiError && error.code === 'RECORD_CHANGED' && error.body.current) {
        // The confirmation now shows what is stored; deleting needs a second, informed press.
        setRecord(error.body.current as UserView);
      } else if (error instanceof ApiError && error.status === 404) {
        setRecord(null);
        markMissing();
      }
      setRetryAt(retryAtOf(error));
      setScreenMessage(problemMessage(error));
      setFocusTick((n) => n + 1);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  usePageKeys({
    back,
    escapeBack: true,
    save: stage === 'ready' ? () => void remove() : undefined,
    cancel: stage === 'ready' ? back : undefined,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'user-delete-message';
  // The prompt names PF5, so it only shows with the function keys on; otherwise the Delete user button says it.
  const prompt = keysOn && stage === 'ready' ? message('USER_DELETE_PROMPT') : null;
  const shownId = record ? printableId(record.userId) : '';

  return (
    <>
      <PageTitle
        title="Delete user"
        description="Check that this is the user you mean. A deleted user can no longer sign on."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      {stage === 'loading' && (
        <div className={classes.searchCard} aria-busy="true">
          <Loader size="sm" aria-label="Loading the user" />
        </div>
      )}
      {stage === 'missing' && <MissingUser retryable={retryable} onRetry={() => void reload()} onBack={back} backLabel="Back to users" />}
      {stage === 'ready' && record && (
        <section className={classes.panel} aria-labelledby="delete-user-title">
          <div className={classes.panelHeader}>
            <div>
              <p className={classes.panelKicker}>User</p>
              <h2 ref={titleRef} tabIndex={-1} id="delete-user-title" className={`${classes.panelTitle} ${users.idCell}`} data-testid="delete-id">
                {shownId}
              </h2>
            </div>
            <TypeBadge type={record.userType} testId="delete-type" />
          </div>
          <dl className={classes.facts}>
            <div>
              <dt>First name</dt>
              <dd data-testid="delete-first-name">{record.firstName || '(blank)'}</dd>
            </div>
            <div>
              <dt>Last name</dt>
              <dd data-testid="delete-last-name">{record.lastName || '(blank)'}</dd>
            </div>
          </dl>
          {prompt && (
            <p className={classes.infoLine} data-testid="info-line">
              {prompt.text}
            </p>
          )}
          <div className={`${classes.confirmBar} ${users.danger}`} data-testid="confirm-bar">
            <Text size="sm" fw={600}>
              Delete {shownId}
              {fullName(record) ? ` (${fullName(record)})` : ''}? They will no longer be able to sign on.
            </Text>
            <div className={classes.searchActions} style={{ paddingTop: 0 }}>
              <Button variant="default" onClick={back} disabled={busy} data-testid="cancel">
                Cancel
                <KeyHint name="F12" />
              </Button>
              <Button color="red" onClick={() => void remove()} loading={busy} data-testid="confirm-delete">
                Delete user
                <KeyHint name="F5" />
              </Button>
            </div>
          </div>
        </section>
      )}
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
