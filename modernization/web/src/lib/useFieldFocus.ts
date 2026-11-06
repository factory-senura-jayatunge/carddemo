import { useCallback, useEffect, useState, type RefObject } from 'react';

/**
 * Moves focus to a field after the render that shows its message, as the
 * legacy screens put the cursor on the field in error.
 */
export function useFieldFocus<F extends string>(refs: Record<F, RefObject<HTMLElement | null>>, initial: F) {
  const [request, setRequest] = useState<{ field: F; n: number }>({ field: initial, n: 0 });
  useEffect(() => {
    const element = refs[request.field].current;
    element?.focus();
    // refs is a stable record of refs; only a new request moves focus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);
  return useCallback((field: F) => setRequest((current) => ({ field, n: current.n + 1 })), []);
}
