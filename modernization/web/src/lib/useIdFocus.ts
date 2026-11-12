import { useCallback, useEffect, useState } from 'react';

/**
 * Moves focus to a field after the render that shows its message, found by its element id: for
 * forms with more fields than refs are worth keeping. `idOf` must be a module-level function.
 */
export function useIdFocus<F extends string>(idOf: (field: F) => string, initial: F) {
  const [request, setRequest] = useState<{ field: F; n: number }>({ field: initial, n: 0 });
  useEffect(() => {
    document.getElementById(idOf(request.field))?.focus();
    // idOf is fixed for the page; only a new request moves focus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);
  return useCallback((field: F) => setRequest((current) => ({ field, n: current.n + 1 })), []);
}
