import { useCallback, useState } from 'react';
import type { UnmaskResult } from '@/api/client';
import { ApiError } from '@/api/http';
import { uiText, type ScreenMessage } from '@/lib/messages';

/** The message-area form of a failed call: the service's text, or the connection note. */
export function problemMessage(error: unknown): ScreenMessage {
  if (error instanceof ApiError && !error.isNetwork) return { text: error.message, tone: error.severity };
  return { text: uiText.noConnection, tone: 'error' };
}

/**
 * The clear values an ADMIN revealed with Show (D-09), one field per audited
 * unmask call. They live only in the page's memory and are dropped with the
 * record they belong to.
 */
export function useReveal(onError: (error: unknown) => void) {
  const [clear, setClear] = useState<Record<string, string | null>>({});
  const [loading, setLoading] = useState<string | null>(null);

  const show = useCallback(
    async (field: string, call: () => Promise<UnmaskResult>) => {
      setLoading(field);
      try {
        const result = await call();
        setClear((current) => ({ ...current, [field]: result.fields[field] ?? null }));
      } catch (error) {
        onError(error);
      } finally {
        setLoading(null);
      }
    },
    [onError],
  );

  const hide = useCallback((field: string) => {
    setClear(({ [field]: _gone, ...rest }) => rest);
  }, []);

  const reset = useCallback(() => setClear({}), []);

  return { clear, loading, show, hide, reset };
}
