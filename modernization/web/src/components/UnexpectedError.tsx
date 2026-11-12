import { Button, Text } from '@mantine/core';
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { readerHome, useBack } from '@/lib/useBack';
import { useUser } from '@/session/SessionProvider';
import classes from './UnexpectedError.module.css';

/** The platform's 500 problem for a failure no step message covers: the catalogue text and a correlation ID, never the cause. */
export const isUnexpected = (error: unknown): error is ApiError => error instanceof ApiError && error.status === 500 && error.code === 'INTERNAL_ERROR';

type Report = (error: unknown) => boolean;
const ReportContext = createContext<Report>(() => false);

/**
 * Report a failed call: an unexpected failure replaces the page with the
 * error page and returns true; any other error returns false for the page to show.
 */
export const useUnexpected = (): Report => useContext(ReportContext);

/**
 * Wraps a page so an unexpected failure ends it with the error page, while the
 * session stays signed on. Try again starts the page afresh. Back goes where
 * the page's own Back would go with no route state: `backTo`, else the reader's home.
 */
export function UnexpectedErrorBoundary({ children, backTo }: { children: ReactNode; backTo?: string }) {
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const report = useCallback<Report>((error) => {
    if (!isUnexpected(error)) return false;
    setFailure(error);
    return true;
  }, []);
  const retry = useCallback(() => {
    setFailure(null);
    setAttempt((n) => n + 1);
  }, []);
  if (failure) return <UnexpectedErrorPage error={failure} onRetry={retry} backTo={backTo} />;
  return (
    <ReportContext.Provider value={report} key={attempt}>
      {children}
    </ReportContext.Provider>
  );
}

function UnexpectedErrorPage({ error, onRetry, backTo }: { error: ApiError; onRetry: () => void; backTo?: string }) {
  const user = useUser();
  const back = useBack(backTo ?? readerHome(user));
  const retryRef = useRef<HTMLButtonElement>(null);
  const reference = typeof error.body.correlationId === 'string' ? error.body.correlationId : null;

  useEffect(() => retryRef.current?.focus(), []);
  // The message area holds the failure; an invalid key must not replace it.
  usePageKeys({ back, escapeBack: true, invalidKey: () => undefined });

  return (
    <div data-testid="error-page">
      <PageTitle
        title="Something went wrong"
        description="The service could not finish this request. You are still signed on."
        actions={
          <Button variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <MessageArea id="error-page-message" message={{ text: error.message, tone: 'error' }}>
        {reference && (
          <Text size="sm" mt={4} c="inherit" data-testid="error-reference">
            Reference <span className="cd-mono">{reference}</span>. Quote it if you report the problem.
          </Text>
        )}
      </MessageArea>
      <div className={classes.actions}>
        <Button ref={retryRef} onClick={onRetry} data-testid="error-retry">
          Try again
        </Button>
      </div>
    </div>
  );
}
