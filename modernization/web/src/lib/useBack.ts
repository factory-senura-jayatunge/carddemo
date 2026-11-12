import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router';

/** Route state of a page opened from another page: Back returns there. */
export interface FromState {
  from?: string;
}

/** The route state that makes Back on the opened page return to `pathname`. */
export const openedFrom = (pathname: string): FromState => ({ from: pathname });

/**
 * Where Back goes from a read-only account or card page opened with no route
 * state: an ADMIN only reaches those pages through admin option 7.
 */
export const readerHome = (user: { role: string; home: string }): string =>
  user.role === 'ADMIN' ? '/admin/lookup' : user.home;

/**
 * Back (PF3): the page that opened this one, else `fallback` (the menu). That
 * is the legacy "from program" rule expressed as route state (web.md, Navigation).
 */
export function useBack(fallback: string): () => void {
  const location = useLocation();
  const navigate = useNavigate();
  const from = (location.state as FromState | null)?.from;
  return useCallback(() => void navigate(from ?? fallback), [navigate, from, fallback]);
}
