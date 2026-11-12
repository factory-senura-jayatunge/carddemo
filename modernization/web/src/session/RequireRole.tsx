import type { ReactNode } from 'react';
import { Navigate } from 'react-router';
import type { Role } from '@/api/client';
import { FullPageLoader } from '@/components/FullPageLoader';
import { useSession } from './SessionProvider';

/**
 * Access model C1: every page needs a session and one of the roles of the
 * route. No session goes to an empty sign-on page; another role goes to its home.
 */
export function RequireRole({ allow, children }: { allow: Role | readonly Role[]; children: ReactNode }) {
  const { state } = useSession();
  if (state.status === 'loading') return <FullPageLoader />;
  if (state.status === 'ending') return null;
  if (state.status === 'anonymous') return <Navigate to="/sign-on" replace />;
  const roles: readonly Role[] = typeof allow === 'string' ? [allow] : allow;
  if (!roles.includes(state.user.role)) return <Navigate to={state.user.home} replace />;
  return children;
}
