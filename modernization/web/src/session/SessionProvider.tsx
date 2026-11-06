import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { identityApi, type SessionView } from '@/api/client';
import { onSessionExpired, refreshSession, unwrap } from '@/api/http';
import { uiText } from '@/lib/messages';
import { messages } from '@/messages';

export type SessionState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; user: SessionView }
  /** The session is over and the app is on its way to a public page. */
  | { status: 'ending' };

/** A neutral note the sign-on page shows after the app ended a session. */
export interface SignOnNote {
  text: string;
}

export interface SignOnRouteState {
  note?: SignOnNote;
  /** Where to return after signing on again (session expiry only). */
  from?: string;
}

export interface SignedOffRouteState {
  message: string;
}

type SignOffTarget =
  /** Header "Sign out" and Exit: the thank-you page (FLOW-SGN-003). */
  | { to: 'signed-off' }
  /** Back on a menu: an empty sign-on page (FLOW-MENU-004, D-05). */
  | { to: 'sign-on'; note?: SignOnNote };

interface SessionApi {
  state: SessionState;
  /** Signs on and returns the session. Throws ApiError on failure. */
  signOn: (userId: string, password: string) => Promise<SessionView>;
  /** Changes the password; the server signs the user on. */
  changePassword: (userId: string, currentPassword: string, newPassword: string) => Promise<SessionView>;
  signOff: (target: SignOffTarget) => Promise<void>;
  /** Keeps the server session alive (idle warning, "Stay signed on"). */
  extend: () => Promise<boolean>;
}

const SessionContext = createContext<SessionApi | null>(null);

async function loadSession(): Promise<SessionView | null> {
  try {
    const { data, response } = await identityApi.GET('/api/auth/me');
    return response.ok && data ? data : null;
  } catch {
    return null;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: 'loading' });
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const here = useRef(location.pathname + location.search);
  useLayoutEffect(() => {
    here.current = location.pathname + location.search;
  }, [location]);

  // GET /api/auth/me first: it restores a session after a reload, and every
  // answer, a 401 included, sets the XSRF-TOKEN cookie the first POST needs.
  useEffect(() => {
    let active = true;
    void loadSession().then((user) => {
      if (active) setState(user ? { status: 'authenticated', user } : { status: 'anonymous' });
    });
    return () => {
      active = false;
    };
  }, []);

  // The session becomes anonymous only once the public page is rendered. The
  // router renders a navigation in a transition, so an earlier "anonymous"
  // would let RequireRole on the old page redirect to a plain /sign-on, which
  // wins over the navigation and drops its route state.
  const endingAt = useRef<string | null>(null);
  useEffect(() => {
    if (state.status === 'ending' && location.pathname === endingAt.current) {
      endingAt.current = null;
      setState({ status: 'anonymous' });
    }
  }, [state.status, location.pathname]);

  const endAndGo = useCallback(
    async (to: '/sign-on' | '/signed-off', state: SignOnRouteState | SignedOffRouteState) => {
      queryClient.clear();
      endingAt.current = to;
      setState({ status: 'ending' });
      await navigate(to, { replace: true, state });
    },
    [queryClient, navigate],
  );

  useEffect(() => {
    onSessionExpired(() => {
      const state: SignOnRouteState = { note: { text: uiText.sessionExpired }, from: here.current };
      void endAndGo('/sign-on', state);
    });
    return () => onSessionExpired(undefined);
  }, [endAndGo]);

  const signOn = useCallback(async (userId: string, password: string) => {
    const user = await unwrap(identityApi.POST('/api/auth/sign-on', { body: { userId, password } }));
    setState({ status: 'authenticated', user });
    return user;
  }, []);

  const changePassword = useCallback(async (userId: string, currentPassword: string, newPassword: string) => {
    const user = await unwrap(identityApi.POST('/api/auth/password', { body: { userId, currentPassword, newPassword } }));
    setState({ status: 'authenticated', user });
    return user;
  }, []);

  const signOff = useCallback(
    async (target: SignOffTarget) => {
      let text: string = messages.SIGNED_OFF.text;
      try {
        const { data } = await identityApi.POST('/api/auth/sign-off');
        if (data?.message) text = data.message;
      } catch {
        // The session ends locally even when the service cannot be reached.
      }
      if (target.to === 'signed-off') {
        const state: SignedOffRouteState = { message: text };
        await endAndGo('/signed-off', state);
      } else {
        const state: SignOnRouteState = target.note ? { note: target.note } : {};
        await endAndGo('/sign-on', state);
      }
    },
    [endAndGo],
  );

  const api = useMemo<SessionApi>(
    () => ({ state, signOn, changePassword, signOff, extend: refreshSession }),
    [state, signOn, changePassword, signOff],
  );

  return <SessionContext.Provider value={api}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionApi {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession outside SessionProvider');
  return value;
}

/** The signed-on user; only for pages behind RequireRole. */
export function useUser(): SessionView {
  const { state } = useSession();
  if (state.status !== 'authenticated') throw new Error('useUser on a public page');
  return state.user;
}
