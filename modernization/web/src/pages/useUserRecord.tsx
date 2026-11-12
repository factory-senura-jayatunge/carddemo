import { Button, Text } from '@mantine/core';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { identityApi, type UserView } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import type { ScreenMessage } from '@/lib/messages';
import { problemMessage } from '@/lib/useReveal';
import { decodeUserKey } from '@/lib/userKey';
import classes from './records.module.css';
import users from './UserPages.module.css';

export type RecordStage = 'loading' | 'ready' | 'missing';

interface Outcome {
  attempt: number;
  stage: 'ready' | 'missing';
  retryable: boolean;
}

/**
 * The user a route's key names, fetched by key. A key that does not decode is missing
 * without a call (contract §1, Transport); the call always sends the key from the route.
 * Pages render this under a `key` of the route's user key, so a new key starts afresh.
 */
export function useUserRecord(
  userKey: string,
  handlers: {
    onLoaded: (user: UserView) => void;
    onFailed: (message: ScreenMessage) => void;
  },
) {
  const decodes = decodeUserKey(userKey) !== null;
  const [attempt, setAttempt] = useState(0);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [gone, setGone] = useState(false);
  const latest = useRef(handlers);
  useLayoutEffect(() => {
    latest.current = handlers;
  });

  useEffect(() => {
    if (!decodes) return;
    let live = true;
    void (async () => {
      try {
        const user = await unwrap(
          identityApi.GET('/api/users/key/{userKey}', {
            params: { path: { userKey } },
          }),
        );
        if (!live) return;
        latest.current.onLoaded(user);
        setOutcome({ attempt, stage: 'ready', retryable: false });
      } catch (error) {
        if (!live) return;
        latest.current.onFailed(problemMessage(error));
        setOutcome({
          attempt,
          stage: 'missing',
          retryable: !(error instanceof ApiError) || error.isNetwork || error.status >= 500,
        });
      }
    })();
    return () => {
      live = false;
    };
  }, [userKey, decodes, attempt]);

  const stage: RecordStage = !decodes || gone ? 'missing' : outcome?.attempt === attempt ? outcome.stage : 'loading';
  return {
    stage,
    retryable: !gone && outcome?.attempt === attempt && outcome.retryable,
    reload: () => {
      setGone(false);
      setAttempt((n) => n + 1);
    },
    /** The user went away after it was shown (404 on a write). */
    markMissing: () => setGone(true),
  };
}

/** The panel in place of a user that could not be opened. */
export function MissingUser({ retryable, onRetry, onBack, backLabel }: { retryable: boolean; onRetry: () => void; onBack: () => void; backLabel: string }) {
  return (
    <div className={classes.panel} data-empty data-testid="missing-user">
      <Text size="sm" c="dimmed" className={users.empty}>
        No user to show.
      </Text>
      <div className={classes.formActions}>
        {retryable && (
          <Button variant="default" onClick={onRetry} data-testid="retry">
            Try again
          </Button>
        )}
        <Button onClick={onBack} data-testid="missing-back">
          {backLabel}
        </Button>
      </div>
    </div>
  );
}
