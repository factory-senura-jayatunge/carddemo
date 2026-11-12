import { useCallback, useEffect, useRef, useState } from 'react';

type FocusTarget = () => HTMLElement | null | undefined;

/**
 * Moves focus once React has committed the render that the same state change asked for, so a field
 * or button that render brings in is there to take it. A frame callback can run before that commit
 * and find nothing, which leaves focus on the page body. While `busy` the request waits: a loading
 * button is disabled and cannot take focus.
 */
export function useFocusAfterCommit(busy = false): (target: FocusTarget) => void {
  const pending = useRef<FocusTarget | null>(null);
  const [request, setRequest] = useState(0);
  useEffect(() => {
    const target = pending.current;
    if (busy || !target) return;
    pending.current = null;
    target()?.focus();
  }, [request, busy]);
  return useCallback((target: FocusTarget) => {
    pending.current = target;
    setRequest((n) => n + 1);
  }, []);
}
